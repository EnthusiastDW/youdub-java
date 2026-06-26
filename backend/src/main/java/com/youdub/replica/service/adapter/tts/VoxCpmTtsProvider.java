package com.youdub.replica.service.adapter.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youdub.replica.model.entity.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VoxCPM TTS 适配器。
 * 通过 HTTP API 调用 VoxCPM 微服务生成声音克隆 TTS。
 */
@Slf4j
@Component("voxcpm")
@RequiredArgsConstructor
public class VoxCpmTtsProvider implements TtsProvider {

    private static final int CONCURRENCY = 4;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${tts.voxcpm.service-url}")
    private String serviceUrl;

    @Qualifier("virtualExecutor")
    private final ExecutorService virtualExecutor;

    @Override
    public String getName() {
        return "voxcpm";
    }

    @Override
    public void synthesize(Task task, Path textPath, Path outputDir) throws Exception {
        if (textPath == null || !Files.exists(textPath)) {
            throw new IllegalArgumentException("翻译文件不存在：" + textPath);
        }

        Path ttsDir = outputDir.resolve("tts");
        Files.createDirectories(ttsDir);

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

        Path refDir = outputDir.resolve("vocals");
        String voxcpmUrl = serviceUrl.endsWith("/") ? serviceUrl.substring(0, serviceUrl.length() - 1) : serviceUrl;

        log.info("调用 VoxCPM API: task={}, 共 {} 句, url={}", task.getId(), items.size(), voxcpmUrl);
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

                        // 构建 multipart 请求
                        var boundary = "----" + java.util.UUID.randomUUID().toString().replace("-", "");
                        var bodyBuilder = new java.io.ByteArrayOutputStream();
                        var crlf = "\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        var boundaryBytes = ("--" + boundary).getBytes(java.nio.charset.StandardCharsets.UTF_8);

                        // text 字段
                        bodyBuilder.write(boundaryBytes);
                        bodyBuilder.write(crlf);
                        bodyBuilder.write("Content-Disposition: form-data; name=\"text\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        bodyBuilder.write(crlf);
                        bodyBuilder.write(crlf);
                        bodyBuilder.write(item.text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        bodyBuilder.write(crlf);

                        // ref_audio 字段（如果存在对应参考音频）
                        Path refAudio = refDir.resolve(String.format("%04d.wav", item.index));
                        if (Files.exists(refAudio) && Files.size(refAudio) > 0) {
                            bodyBuilder.write(boundaryBytes);
                            bodyBuilder.write(crlf);
                            bodyBuilder.write("Content-Disposition: form-data; name=\"ref_audio\"; filename=\"ref.wav\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            bodyBuilder.write(crlf);
                            bodyBuilder.write("Content-Type: audio/wav".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            bodyBuilder.write(crlf);
                            bodyBuilder.write(crlf);
                            bodyBuilder.write(Files.readAllBytes(refAudio));
                            bodyBuilder.write(crlf);
                        }

                        // 结束 boundary
                        bodyBuilder.write(("--" + boundary + "--").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        bodyBuilder.write(crlf);

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(voxcpmUrl))
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBuilder.toByteArray()))
                                .build();

                        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                        if (response.statusCode() != 200) {
                            String errorBody = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                            throw new RuntimeException("VoxCPM API 调用失败 [" + response.statusCode() + "]: " + errorBody);
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
                    throw new RuntimeException("VoxCPM TTS 失败：" + item.text, e);
                }
            }, virtualExecutor);
            futures.add(future);
        }

        for (CompletableFuture<Void> f : futures) {
            f.join();
        }
        log.info("VoxCPM TTS 完成：task={}, dir={}", task.getId(), ttsDir);
    }

    private record TtsItem(int index, String text) {}
}
