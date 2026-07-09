package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;

import com.youdub.replica.service.SettingsService;
import com.youdub.replica.util.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.AUDIO_SEPARATOR_API;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
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
@Component(AUDIO_SEPARATOR_API)
@RequiredArgsConstructor
public class AudioSeparatorApiSeparator extends BaseSourceSeparator {
    private static final String SEPARATE_ENDPOINT = "/api/v1/separate";

    private final OkHttpClient httpClient;
    private final SettingsService settingsService;

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
        if (Files.exists(vocalsOut)) {
            log.info("分离结果已存在，跳过：{}", outputDir);
            return;
        }

        Path audioToSend = extractAudio(task, audioPath, outputDir);
        boolean isTemp = !audioToSend.equals(audioPath);
        Path zipTemp = null;
        boolean zipTempCleanup = false;

        try {
            String serviceUrl = settingsService.getProviderConfig(AUDIO_SEPARATOR_API, AppProperties.Separate.AudioSeparatorApi.class).getServiceUrl();
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
            String filename = audioToSend.getFileName().toString();
            RequestBody multipartBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename,
                            RequestBody.create(audioToSend.toFile(), MediaType.parse("application/octet-stream")))
                    .build();

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(multipartBody)
                    .build();

            log.info("开始流式上传音频：task={}, size={}MB", task.getId(), audioSize / (1024 * 1024));

            long tApi = System.currentTimeMillis();
            zipTemp = outputDir.resolve("separated_" + task.getId() + "_" + UUID.randomUUID() + ".zip");
            Response response;
            try {
                response = HttpUtil.sendInterruptible(httpClient, request);
            } catch (IOException e) {
                throw new RuntimeException("audio-separator 服务连接失败（请确认服务已启动且可达：" + apiUrl + "）：" + e.getMessage(), e);
            }
            long apiElapsed = System.currentTimeMillis() - tApi;

            int statusCode = response.code();
            okhttp3.ResponseBody respBody = response.body();

            if (statusCode != 200) {
                String errorMsg = respBody != null ? respBody.string() : "无响应内容";
                throw new RuntimeException("audio-separator API 调用失败 [" + statusCode + "]：" + errorMsg);
            }

            if (respBody == null) {
                throw new RuntimeException("audio-separator API 返回空响应");
            }

            try (var sink = Files.newOutputStream(zipTemp);
                 var source = respBody.byteStream()) {
                source.transferTo(sink);
            }
            zipTempCleanup = true;
            Path zipPath = zipTemp;

            if (Files.size(zipPath) == 0) {
                throw new RuntimeException("audio-separator API 返回空响应");
            }

            log.info("API 分离完成：task={}, duration={}ms, zip={}bytes",
                    task.getId(), apiElapsed, Files.size(zipPath));

            long tZip = System.currentTimeMillis();
            extractFromZip(zipPath, vocalsOut, bgmOut);
            log.info("ZIP 解压完成：task={}, duration={}ms", task.getId(), System.currentTimeMillis() - tZip);

            long vocalSize = Files.size(vocalsOut);
            long bgmSize = Files.size(bgmOut);
            log.info("API 分离总完成：task={}, total={}ms, vocals={}MB, bgm={}MB",
                    task.getId(), System.currentTimeMillis() - tTotal,
                    vocalSize / (1024 * 1024), bgmSize / (1024 * 1024));
        } finally {
            if (zipTempCleanup) {
                try { Files.deleteIfExists(zipTemp); } catch (IOException e) { log.warn("清理临时 ZIP 失败：{}", zipTemp, e); }
            }
            if (isTemp) {
                Files.deleteIfExists(audioToSend);
            }
        }
    }

    /**
     * 从 ZIP 响应中提取 vocals.wav 和 instrumental.wav
     */
    private void extractFromZip(Path zipPath, Path vocalsOut, Path bgmOut) throws IOException {
        try (InputStream is = Files.newInputStream(zipPath);
             ZipInputStream zis = new ZipInputStream(is)) {
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
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }
}