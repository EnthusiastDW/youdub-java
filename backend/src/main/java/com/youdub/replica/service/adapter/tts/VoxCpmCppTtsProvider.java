package com.youdub.replica.service.adapter.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.youdub.replica.service.adapter.AdapterConstants.VOXCPM_CPP;

/**
 * VoxCPM2 C++ TTS 适配器（llama.cpp-omni voxcpm2-cli 子进程）。
 * 通过本机编译的 voxcpm2-cli 生成声音克隆 TTS，模型 GGUF 懒下载。
 */
@Slf4j
@Component(VOXCPM_CPP)
@RequiredArgsConstructor
public class VoxCpmCppTtsProvider implements TtsProvider {

    private static final String DEFAULT_PATH = "voxcpm2-cli";
    private static final String DEFAULT_MODEL_DIR = "data/voxcpm-models";
    private static final long DEFAULT_TIMEOUT_MS = 600_000L;
    private static final int DEFAULT_CONCURRENCY = 1;
    private static final double DEFAULT_CFG_VALUE = 2.0;
    private static final int DEFAULT_TIMESTEPS = 10;
    private static final int DEFAULT_SEED = 42;

    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Qualifier("virtualExecutor")
    private final ExecutorService virtualExecutor;

    @Override
    public void synthesize(Task task, Path textPath, Path outputDir) throws Exception {
        if (textPath == null || !Files.exists(textPath)) {
            throw new IllegalArgumentException("翻译文件不存在：" + textPath);
        }

        Path ttsDir = outputDir.resolve("tts");
        Files.createDirectories(ttsDir);

        AppProperties.Tts.VoxcpmCpp config =
                settingsService.getProviderConfig(VOXCPM_CPP, AppProperties.Tts.VoxcpmCpp.class);

        String cliPath = (config.getPath() == null || config.getPath().isBlank())
                ? DEFAULT_PATH : config.getPath();
        int concurrency = config.getConcurrency() > 0 ? config.getConcurrency() : DEFAULT_CONCURRENCY;
        long timeoutMs = config.getTimeoutMs() > 0 ? config.getTimeoutMs() : DEFAULT_TIMEOUT_MS;

        Path modelDir = resolveModelDir(config);
        Path baseLmPath = VoxCpmCppModels.ensureBaseLmModel(modelDir,
                notBlank(config.getBaseLmModel(), VoxCpmCppModels.BASE_LM_MODEL));
        Path acousticPath = VoxCpmCppModels.ensureAcousticModel(modelDir,
                notBlank(config.getAcousticModel(), VoxCpmCppModels.ACOUSTIC_MODEL));

        JsonNode root = objectMapper.readTree(Files.readString(textPath));
        JsonNode translation = root.path("translation");
        if (!translation.isArray() || translation.isEmpty()) {
            log.warn("翻译结果为空，跳过 TTS");
            return;
        }

        Path refDir = outputDir.resolve("vocals");

        List<TtsItem> items = new ArrayList<>();
        int vocalIdx = 0;
        for (JsonNode item : translation) {
            long startMs = item.path("start_time").asLong(0);
            long endMs = item.path("end_time").asLong(0);
            String text = item.path("dst").asText("").trim();
            if (text.isEmpty()) {
                if (endMs > startMs) {
                    vocalIdx++;
                }
                continue;
            }
            if (endMs <= startMs) {
                continue;
            }
            items.add(new TtsItem(items.size(), text, vocalIdx));
            vocalIdx++;
        }

        if (items.isEmpty()) {
            log.warn("没有需要 TTS 的句子");
            return;
        }

        log.info("执行 voxcpm2-cli：task={}, 共 {} 句, model={}+{}",
                task.getId(), items.size(), baseLmPath.getFileName(), acousticPath.getFileName());
        Semaphore semaphore = new Semaphore(concurrency);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicBoolean stopped = new AtomicBoolean(false);
        Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();
        int total = items.size();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (TtsItem item : items) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        if (stopped.get() || Thread.currentThread().isInterrupted()) {
                            return;
                        }

                        Path outputFile = ttsDir.resolve(String.format("%04d.wav", item.index));
                        if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
                            log.debug("TTS 输出已存在，跳过：{}", outputFile);
                            return;
                        }

                        List<String> command = buildCommand(cliPath, config, item, refDir, outputFile,
                                baseLmPath, acousticPath);

                        try {
                            CommandRunner.run(Command.builder()
                                            .add(command)
                                            .timeout(timeoutMs)
                                            .workDir(ttsDir)
                                            .build(),
                                    process -> activeProcesses.add(process));
                        } catch (RuntimeException e) {
                            log.warn("voxcpm2-cli 失败，跳过该句：task={}, index={}, text='{}', error={}",
                                    task.getId(), item.index, item.text, e.getMessage());
                            return;
                        }
                        int done = completed.incrementAndGet();
                        if (done % 10 == 0 || done == total) {
                            log.info("TTS 进度：{}/{}", done, total);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, virtualExecutor);
            futures.add(future);
        }

        try {
            for (CompletableFuture<Void> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped.set(true);
            futures.forEach(f -> f.cancel(true));
            for (Process p : activeProcesses) {
                if (p.isAlive()) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
            }
            throw new RuntimeException("TTS 被用户中止", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
        log.info("voxcpm2-cli 完成：task={}, dir={}", task.getId(), ttsDir);
    }

    private List<String> buildCommand(String cliPath, AppProperties.Tts.VoxcpmCpp config, TtsItem item,
                                      Path refDir, Path outputFile, Path baseLmPath, Path acousticPath) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(cliPath);
        command.add("-t");
        command.add(item.text);
        Path refAudio = refDir.resolve(String.format("%04d.wav", item.vocalIdx));
        if (Files.exists(refAudio) && Files.size(refAudio) > 0) {
            command.add("-r");
            command.add(refAudio.toString());
        }
        command.add("-o");
        command.add(outputFile.toString());
        command.add("--cfg");
        command.add(String.valueOf(config.getCfgValue() > 0 ? config.getCfgValue() : DEFAULT_CFG_VALUE));
        command.add("--timesteps");
        command.add(String.valueOf(config.getTimesteps() > 0 ? config.getTimesteps() : DEFAULT_TIMESTEPS));
        command.add("--seed");
        command.add(String.valueOf(config.getSeed() >= 0 ? config.getSeed() : DEFAULT_SEED));
        // 后端容器无 GPU，强制 CPU 后端
        command.add("--cpu");
        command.add(baseLmPath.toString());
        command.add(acousticPath.toString());
        return command;
    }

    private Path resolveModelDir(AppProperties.Tts.VoxcpmCpp config) {
        String modelDir = config.getModelDir();
        if (modelDir == null || modelDir.isBlank()) {
            modelDir = DEFAULT_MODEL_DIR;
        }
        return Paths.get(modelDir).toAbsolutePath();
    }

    private static String notBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record TtsItem(int index, String text, int vocalIdx) {}
}
