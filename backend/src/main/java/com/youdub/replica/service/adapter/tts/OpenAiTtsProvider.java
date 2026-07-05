package com.youdub.replica.service.adapter.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.OPENAI_TTS;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
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

    private final HttpClient httpClient;
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

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (TtsItem item : items) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
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

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(config.getUrl()))
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                                .build();

                        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                        if (response.statusCode() != 200) {
                            throw new RuntimeException("OpenAI TTS API 调用失败 [" + response.statusCode() + "]");
                        }
                        Files.write(outputFile, response.body());

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
            }, virtualExecutor);
            futures.add(future);
        }

        for (CompletableFuture<Void> f : futures) {
            f.join();
        }
        log.info("OpenAI TTS 完成：task={}, dir={}", task.getId(), ttsDir);
    }

    private record TtsItem(int index, String text) {
    }
}