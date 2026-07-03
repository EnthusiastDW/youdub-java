package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Audio Separator API 适配器。
 * 通过 HTTP 调用 docker audio-separator 服务进行人声分离。
 * API 返回包含 vocals.wav 和 instrumental.wav 的 ZIP 文件。
 */
@Slf4j
@Component("audio-separator-api")
@RequiredArgsConstructor
public class AudioSeparatorApiSeparator extends BaseSourceSeparator {

    private static final String SEPARATE_ENDPOINT = "/api/v1/separate";

    private final HttpClient httpClient;
    private final AppProperties.Separate.AudioSeparatorApi audioSeparatorApiConfig;

    @Override
    public String getName() {
        return "audio-separator-api";
    }

    @Override
    public void separate(Task task, Path audioPath, Path outputDir, String device) throws Exception {
        long tTotal = System.currentTimeMillis();

        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }

        long inputSize = Files.size(audioPath);
        log.info("API 分离开始：task={}, input={}, size={}MB", task.getId(), audioPath, inputSize / (1024 * 1024));

        Files.createDirectories(outputDir);

        Path vocalsOut = outputDir.resolve("audio_vocals.wav");
        Path bgmOut = outputDir.resolve("audio_bgm.wav");
        if (Files.exists(vocalsOut) && Files.exists(bgmOut)) {
            log.info("分离结果已存在，跳过：{}", outputDir);
            return;
        }

        Path audioToSend = extractAudio(task, audioPath, outputDir);
        boolean isTemp = !audioToSend.equals(audioPath);

        try {
            String serviceUrl = audioSeparatorApiConfig.getServiceUrl();
            if (serviceUrl == null || serviceUrl.isBlank()) {
                throw new IllegalArgumentException("audio-separator-api.serviceUrl 未配置");
            }
            String apiUrl = serviceUrl.endsWith("/") ? serviceUrl.substring(0, serviceUrl.length() - 1) : serviceUrl;
            apiUrl += SEPARATE_ENDPOINT;

            long audioSize = Files.size(audioToSend);
            log.info("调用 audio-separator API：task={}, audio={}, size={}MB, url={}",
                    task.getId(), audioToSend, audioSize / (1024 * 1024), apiUrl);

            if (audioSize == 0) {
                throw new IllegalArgumentException("音频文件为空：" + audioPath);
            }

            // 流式构建 multipart/form-data，避免将大文件全部读入堆内存
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
            String filename = audioToSend.getFileName().toString();
            byte[] headerBytes = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] footerBytes = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

            var bodyPublisher = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(headerBytes),
                    HttpRequest.BodyPublishers.ofFile(audioToSend),
                    HttpRequest.BodyPublishers.ofByteArray(footerBytes));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(bodyPublisher)
                    .build();

            log.info("开始流式上传音频：task={}, size={}MB", task.getId(), audioSize / (1024 * 1024));

            long tApi = System.currentTimeMillis();
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                throw new RuntimeException("audio-separator 服务连接失败（请确认服务已启动且可达：" + apiUrl + "）：" + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("audio-separator 请求被中断", e);
            }
            long apiElapsed = System.currentTimeMillis() - tApi;

            int statusCode = response.statusCode();
            byte[] responseBody = response.body();

            if (statusCode != 200) {
                String errorMsg = responseBody != null ? new String(responseBody) : "无响应内容";
                throw new RuntimeException("audio-separator API 调用失败 [" + statusCode + "]：" + errorMsg);
            }

            if (responseBody == null || responseBody.length == 0) {
                throw new RuntimeException("audio-separator API 返回空响应");
            }

            log.info("API 分离完成：task={}, duration={}ms, response={}bytes", task.getId(), apiElapsed, responseBody.length);

            long tZip = System.currentTimeMillis();
            extractFromZip(responseBody, vocalsOut, bgmOut);
            log.info("ZIP 解压完成：task={}, duration={}ms", task.getId(), System.currentTimeMillis() - tZip);

            long vocalSize = Files.size(vocalsOut);
            long bgmSize = Files.size(bgmOut);
            log.info("API 分离总完成：task={}, total={}ms, vocals={}MB, bgm={}MB",
                    task.getId(), System.currentTimeMillis() - tTotal,
                    vocalSize / (1024 * 1024), bgmSize / (1024 * 1024));
        } finally {
            if (isTemp) {
                Files.deleteIfExists(audioToSend);
            }
        }
    }

    /**
     * 从 ZIP 响应中提取 vocals.wav 和 instrumental.wav
     */
    private void extractFromZip(byte[] zipData, Path vocalsOut, Path bgmOut) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            boolean foundVocals = false;
            boolean foundInstrumental = false;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName().toLowerCase();
                Path targetPath = null;

                if (entryName.equals("vocals.wav") || entryName.endsWith("/vocals.wav")) {
                    targetPath = vocalsOut;
                    foundVocals = true;
                } else if (entryName.equals("instrumental.wav") || entryName.endsWith("/instrumental.wav")) {
                    targetPath = bgmOut;
                    foundInstrumental = true;
                }

                if (targetPath != null) {
                    Files.copy(zis, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    log.debug("提取 ZIP 条目：{} -> {}", entry.getName(), targetPath);
                }
                zis.closeEntry();
            }

            if (!foundVocals) {
                throw new IOException("ZIP 响应中未找到 vocals.wav");
            }
            if (!foundInstrumental) {
                throw new IOException("ZIP 响应中未找到 instrumental.wav");
            }
        }
    }
}