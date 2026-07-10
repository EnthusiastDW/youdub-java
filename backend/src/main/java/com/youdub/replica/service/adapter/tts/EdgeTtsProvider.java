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

import static com.youdub.replica.service.adapter.AdapterConstants.EDGE_TTS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Edge-TTS TTS 适配器。
 * 通过 edge-tts 子进程生成通用 TTS 音频。
 */
@Slf4j
@Component(EDGE_TTS)
@RequiredArgsConstructor
public class EdgeTtsProvider implements TtsProvider {

    private static final long TIMEOUT_MS = 120_000L;
    private static final int CONCURRENCY = 8;
    private static final String DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural";

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

        AppProperties.Tts.EdgeTts config = settingsService.getProviderConfig(EDGE_TTS, AppProperties.Tts.EdgeTts.class);
        String edgePath = config.getPath();
        // 优先读取用户通过设置页面保存的音色，未设置时回退到配置文件默认值
        String voice = config.getVoice();
        String useVoice = (voice == null || voice.isBlank()) ? DEFAULT_VOICE : voice;

        // 读取翻译结果
        JsonNode root = objectMapper.readTree(Files.readString(textPath));
        JsonNode translation = root.path("translation");
        if (!translation.isArray() || translation.isEmpty()) {
            log.warn("翻译结果为空，跳过 TTS");
            return;
        }

        List<TtsItem> items = new ArrayList<>();
        for (JsonNode item : translation) {
            String text = item.path("dst").asText("").trim();
            if (text.isEmpty()) {
                continue;
            }
            items.add(new TtsItem(items.size(), text));
        }

        if (items.isEmpty()) {
            log.warn("没有需要 TTS 的句子");
            return;
        }

        log.info("执行 Edge-TTS：task={}, 共 {} 句, voice={}", task.getId(), items.size(), useVoice);
        Semaphore semaphore = new Semaphore(CONCURRENCY);
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
                        // 停止检查：被取消后不再启动新的 edge-tts 进程
                        if (stopped.get() || Thread.currentThread().isInterrupted()) {
                            return;
                        }

                        Path outputFile = ttsDir.resolve(String.format("%04d.wav", item.index));
                        if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
                            log.debug("TTS 输出已存在，跳过：{}", outputFile);
                            return;
                        }

                        List<String> command = new ArrayList<>();
                        command.add(edgePath);
                        command.add("--voice");
                        command.add(useVoice);
                        command.add("--text");
                        command.add(item.text);
                        command.add("--write-media");
                        command.add(outputFile.toString());

                        try {
                            CommandRunner.run(Command.builder()
                                            .add(command)
                                            .timeout(TIMEOUT_MS)
                                            .workDir(ttsDir)
                                            .build(),
                                    process -> activeProcesses.add(process));
                        } catch (RuntimeException e) {
                            log.warn("Edge-TTS 失败，跳过该句：task={}, index={}, text='{}', error={}",
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
            // 直接杀所有已启动的 edge-tts 进程
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
        log.info("Edge-TTS 完成：task={}, dir={}", task.getId(), ttsDir);
    }

    private record TtsItem(int index, String text) {}
}
