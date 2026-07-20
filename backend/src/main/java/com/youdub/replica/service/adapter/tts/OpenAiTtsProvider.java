package com.youdub.replica.service.adapter.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;
import com.youdub.replica.util.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.OPENAI_TTS;

import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI TTS 适配器。
 * 通过 OpenAI /audio/speech 端点生成 TTS 音频。
 */
@Slf4j
@Component(OPENAI_TTS)
@RequiredArgsConstructor
public class OpenAiTtsProvider implements TtsProvider {

    private static final int CONCURRENCY = 8;

    private final OkHttpClient httpClient;
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

        AppProperties.Tts.OpenaiTts config = settingsService.getProviderConfig(OPENAI_TTS, AppProperties.Tts.OpenaiTts.class);
        String apiKey = config.getApiKey();
        String useVoice = config.getVoice();

        if (apiKey.isBlank()) {
            throw new RuntimeException("未配置 OPENAI_API_KEY 环境变量");
        }

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

        log.info("执行 OpenAI TTS：task={}, 共 {} 句, voice={}", task.getId(), items.size(), useVoice);
        Semaphore semaphore = new Semaphore(CONCURRENCY);
        AtomicInteger completed = new AtomicInteger(0);
        int total = items.size();

        List<Future<?>> futures = new ArrayList<>();
        for (TtsItem item : items) {
            Future<?> future = virtualExecutor.submit(() -> {
                try {
                    semaphore.acquire();
                    try {
                        Path outputFile = ttsDir.resolve(String.format("%04d.wav", item.index));
                        if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
                            log.debug("TTS 输出已存在，跳过：{}", outputFile);
                            return;
                        }

                        ObjectNode requestBody = objectMapper.createObjectNode();
                        requestBody.put("model", config.getModel());
                        requestBody.put("input", item.text);
                        requestBody.put("voice", useVoice);
                        requestBody.put("response_format", "wav");

                        Request request = new Request.Builder()
                                .url(config.getUrl())
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .post(RequestBody.create(
                                        objectMapper.writeValueAsString(requestBody),
                                        MediaType.parse("application/json; charset=utf-8")))
                                .build();

                        Response response = HttpUtil.sendInterruptible(httpClient, request);
                        byte[] audioData = response.body() != null ? response.body().bytes() : new byte[0];
                        if (response.code() != 200) {
                            throw new RuntimeException("OpenAI TTS API 调用失败 [" + response.code() + "]");
                        }
                        Files.write(outputFile, audioData);

                        int done = completed.incrementAndGet();
                        if (done % 10 == 0 || done == total) {
                            log.info("TTS 进度：{}/{}", done, total);
                        }
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("TTS 被中断", e);
                } catch (Exception e) {
                    throw new RuntimeException("OpenAI TTS 失败：" + item.text, e);
                }
            });
            futures.add(future);
        }

        try {
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures.forEach(f -> f.cancel(true));
            throw new RuntimeException("TTS 被用户中止", e);
        } catch (java.util.concurrent.ExecutionException e) {
            futures.forEach(f -> f.cancel(true));
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
        log.info("OpenAI TTS 完成：task={}, dir={}", task.getId(), ttsDir);
    }

    private record TtsItem(int index, String text) {
    }
}