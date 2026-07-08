package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;

import com.youdub.replica.util.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.WHISPER_API;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OpenAI Whisper API 语音识别适配器。
 * 通过 OpenAI /audio/transcriptions 端点进行语音识别。
 */
@Slf4j
@Component(WHISPER_API)
@RequiredArgsConstructor
public class WhisperApiRecognizer implements SpeechRecognizer {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Override
    public void transcribe(Task task, Path audioPath, Path outputDir, String language) throws Exception {
        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }
        Files.createDirectories(outputDir);

        var config = settingsService.getProviderConfig(WHISPER_API, AppProperties.Asr.WhisperApi.class);

        Path asrFile = outputDir.resolve("asr.json");
        if (Files.exists(asrFile)) {
            log.info("ASR 结果已存在，跳过：{}", asrFile);
            return;
        }

        String apiKey = config.getApiKey();
        if (apiKey.isBlank()) {
            throw new RuntimeException("未配置 WHISPER_API_KEY 环境变量");
        }

        byte[] audioBytes = Files.readAllBytes(audioPath);

        String resolvedUrl = config.getUrl();
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            String normBase = config.getBaseUrl().endsWith("/") ? config.getBaseUrl().substring(0, config.getBaseUrl().length() - 1) : config.getBaseUrl();
            resolvedUrl = normBase + "/v1/audio/transcriptions";
        }

        // 构建 URL（含查询参数），避免 multipart 编码导致二进制损坏
        StringBuilder urlBuilder = new StringBuilder(resolvedUrl);
        urlBuilder.append("?model=").append(URLEncoder.encode(config.getModel(), StandardCharsets.UTF_8));
        urlBuilder.append("&response_format=").append(URLEncoder.encode("verbose_json", StandardCharsets.UTF_8));
        if (language != null && !language.isBlank()) {
            urlBuilder.append("&language=").append(URLEncoder.encode(language, StandardCharsets.UTF_8));
        }

        // 直接发送原始二进制音频
        Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/octet-stream")
                .post(RequestBody.create(audioBytes, MediaType.parse("application/octet-stream")))
                .build();

        log.info("调用 OpenAI Whisper API：task={}, audio={}, url={}", task.getId(), audioPath, urlBuilder);
        long s = System.currentTimeMillis();
        Response response = HttpUtil.sendInterruptible(httpClient, request);
        log.info("调用 OpenAI Whisper API 耗时：{} min", (System.currentTimeMillis() - s) / 3600);
        String body = response.body() != null ? response.body().string() : "";
        if (response.code() != 200) {
            throw new RuntimeException("Whisper API 调用失败 [" + response.code() + "]：" + body);
        }

        JsonNode root = objectMapper.readTree(body);
        ObjectNode result = convertToStandardFormat(root, audioPath);
        Files.writeString(asrFile, objectMapper.writeValueAsString(result));
        log.info("ASR 完成：task={}, file={}", task.getId(), asrFile);
    }

    /**
     * 将 OpenAI Whisper verbose_json 响应转换为内部标准格式。
     */
    private ObjectNode convertToStandardFormat(JsonNode apiResponse, Path audioPath) {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode audioInfo = objectMapper.createObjectNode();
        audioInfo.put("duration", apiResponse.path("duration").asDouble(0.0) * 1000);
        audioInfo.put("source", audioPath.toString());
        result.set("audio_info", audioInfo);

        ObjectNode resultObj = objectMapper.createObjectNode();
        resultObj.put("text", apiResponse.path("text").asText(""));

        ArrayNode utterances = objectMapper.createArrayNode();
        JsonNode segments = apiResponse.path("segments");
        if (segments.isArray()) {
            for (JsonNode seg : segments) {
                ObjectNode utterance = objectMapper.createObjectNode();
                utterance.put("text", seg.path("text").asText("").trim());
                utterance.put("start_time", (long) (seg.path("start").asDouble(0.0) * 1000));
                utterance.put("end_time", (long) (seg.path("end").asDouble(0.0) * 1000));
                utterance.put("speaker", "1");

                ArrayNode words = objectMapper.createArrayNode();
                JsonNode wordsNode = seg.path("words");
                if (wordsNode.isArray()) {
                    for (JsonNode w : wordsNode) {
                        ObjectNode word = objectMapper.createObjectNode();
                        word.put("text", w.path("word").asText(""));
                        word.put("start_time", (long) (w.path("start").asDouble(0.0) * 1000));
                        word.put("end_time", (long) (w.path("end").asDouble(0.0) * 1000));
                        words.add(word);
                    }
                }
                utterance.set("words", words);
                utterances.add(utterance);
            }
        }
        resultObj.set("utterances", utterances);
        result.set("result", resultObj);
        return result;
    }

}
