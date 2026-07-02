package com.youdub.replica.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.dto.SettingsRequest;
import com.youdub.replica.dto.SettingsResponse;
import com.youdub.replica.repository.SettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置服务。
 * 管理应用设置（OpenAI、yt-dlp 代理、YouTube cookie 等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final String YOUTUBE_COOKIE_FILE = "youtube.txt";

    private final SettingsRepository settingsRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * 获取所有设置（从 DB + 环境变量合并）。
     */
    public SettingsResponse getSettings() {
        SettingsResponse resp = new SettingsResponse();

        SettingsResponse.YtdlpSettings ytdlp = new SettingsResponse.YtdlpSettings();
        ytdlp.setProxy(settingsRepository.get("ytdlp.proxy", appProperties.getYtdlp().getProxy()));
        resp.setYtdlp(ytdlp);

        resp.setYoutubeCookie(buildCookieInfo());
        resp.setProviders(buildProvidersData());
        return resp;
    }

    /**
     * 保存设置到 DB。
     */
    public SettingsResponse saveSettings(SettingsRequest request) {
        if (request.getProviders() != null) {
            SettingsRequest.ProviderSelections p = request.getProviders();
            if (p.getAsr() != null) settingsRepository.set("asr.provider", p.getAsr());
            if (p.getTts() != null) settingsRepository.set("tts.provider", p.getTts());
            if (p.getTranslate() != null) settingsRepository.set("translate.provider", p.getTranslate());
            if (p.getSeparate() != null) settingsRepository.set("separate.provider", p.getSeparate());

            if (p.getProviderConfigs() != null) {
                for (Map.Entry<String, String> entry : p.getProviderConfigs().entrySet()) {
                    settingsRepository.set(entry.getKey(), entry.getValue());
                }
            }
        }

        if (request.getYtdlp() != null && request.getYtdlp().getProxy() != null) {
            settingsRepository.set("ytdlp.proxy", request.getYtdlp().getProxy());
        }

        if (request.getYoutubeCookie() != null) {
            saveYouTubeCookie(request.getYoutubeCookie().getContent());
        }

        return getSettings();
    }

    /**
     * 调用 OpenAI /models API 获取模型列表。
     */
    public List<String> getOpenAiModels(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            String chatUrl = settingsRepository.get("translate.openai.chatUrl", appProperties.getTranslate().getOpenai().getChatUrl());
            baseUrl = normalizeBaseUrl(chatUrl.replace("/chat/completions", ""));
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = settingsRepository.get("translate.openai.apiKey", appProperties.getTranslate().getOpenai().getApiKey());
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("未配置 OpenAI API key");
        }

        String normalizedUrl = normalizeBaseUrl(baseUrl) + "/models";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizedUrl))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenAI API 调用失败 [" + response.statusCode() + "]：" + response.body());
            }
            List<String> models = new ArrayList<>();
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String id = node.path("id").asText("");
                    if (!id.isEmpty()) {
                        models.add(id);
                    }
                }
            }
            return models;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取模型列表失败：{}", e.getMessage());
            throw new RuntimeException("获取模型列表失败：" + e.getMessage());
        }
    }

    /**
     * 返回 Cookie 文件路径。
     */
    public String getYouTubeCookiePath() {
        return Paths.get(appProperties.getCookieDir(), YOUTUBE_COOKIE_FILE).toString();
    }

    /**
     * 保存 Cookie 文件。
     */
    public void saveYouTubeCookie(String content) {
        try {
            Path cookieDir = Paths.get(appProperties.getCookieDir());
            Files.createDirectories(cookieDir);
            Path cookieFile = cookieDir.resolve(YOUTUBE_COOKIE_FILE);
            if (content == null || content.isBlank()) {
                Files.deleteIfExists(cookieFile);
            } else {
                Files.writeString(cookieFile, content);
            }
        } catch (Exception e) {
            log.error("保存 cookie 文件失败：{}", e.getMessage());
            throw new RuntimeException("保存 cookie 文件失败：" + e.getMessage());
        }
    }

    private SettingsResponse.YoutubeCookieInfo buildCookieInfo() {
        SettingsResponse.YoutubeCookieInfo info = new SettingsResponse.YoutubeCookieInfo();
        Path cookieFile = Paths.get(appProperties.getCookieDir(), YOUTUBE_COOKIE_FILE);
        info.setExists(Files.exists(cookieFile));
        if (info.isExists()) {
            try {
                info.setSize(Files.size(cookieFile));
                info.setUpdatedAt(Files.getLastModifiedTime(cookieFile).toString());
            } catch (Exception e) {
                log.warn("读取 cookie 文件元信息失败：{}", e.getMessage());
            }
        }
        info.setContent("");
        return info;
    }

    /**
     * 从 AppProperties 加载所有 provider 配置。
     */
    private SettingsResponse.ProvidersData buildProvidersData() {
        SettingsResponse.ProvidersData data = new SettingsResponse.ProvidersData();

        // ASR
        SettingsResponse.ProviderGroup asr = data.getAsr();
        asr.setCurrent(settingsRepository.get("asr.provider", appProperties.getAsr().getProvider()));

        Map<String, String> whisperApi = new LinkedHashMap<>();
        whisperApi.put("baseUrl", settingsRepository.get("asr.whisper-api.baseUrl", appProperties.getAsr().getWhisperApi().getBaseUrl()));
        whisperApi.put("url", settingsRepository.get("asr.whisper-api.url", appProperties.getAsr().getWhisperApi().getUrl()));
        whisperApi.put("apiKey", settingsRepository.get("asr.whisper-api.apiKey", appProperties.getAsr().getWhisperApi().getApiKey()));
        whisperApi.put("model", settingsRepository.get("asr.whisper-api.model", appProperties.getAsr().getWhisperApi().getModel()));
        asr.getOptions().put("whisper-api", whisperApi);

        Map<String, String> whisperCpp = new LinkedHashMap<>();
        whisperCpp.put("model", settingsRepository.get("asr.whisper-cpp.model", appProperties.getAsr().getWhisperCpp().getModel()));
        asr.getOptions().put("whisper-cpp", whisperCpp);

        // TTS
        SettingsResponse.ProviderGroup tts = data.getTts();
        tts.setCurrent(settingsRepository.get("tts.provider", appProperties.getTts().getProvider()));

        Map<String, String> ttsEdge = new LinkedHashMap<>();
        ttsEdge.put("path", settingsRepository.get("tts.edge-tts.path", appProperties.getTts().getEdgeTts().getPath()));
        ttsEdge.put("voice", settingsRepository.get("tts.edge-tts.voice", appProperties.getTts().getEdgeTts().getVoice()));
        tts.getOptions().put("edge-tts", ttsEdge);

        Map<String, String> ttsOpenai = new LinkedHashMap<>();
        ttsOpenai.put("url", settingsRepository.get("tts.openai-tts.url", appProperties.getTts().getOpenaiTts().getUrl()));
        ttsOpenai.put("apiKey", settingsRepository.get("tts.openai-tts.apiKey", appProperties.getTts().getOpenaiTts().getApiKey()));
        ttsOpenai.put("model", settingsRepository.get("tts.openai-tts.model", appProperties.getTts().getOpenaiTts().getModel()));
        ttsOpenai.put("voice", settingsRepository.get("tts.openai-tts.voice", appProperties.getTts().getOpenaiTts().getVoice()));
        tts.getOptions().put("openai-tts", ttsOpenai);

        Map<String, String> ttsVoxcpm = new LinkedHashMap<>();
        ttsVoxcpm.put("serviceUrl", settingsRepository.get("tts.voxcpm.serviceUrl", appProperties.getTts().getVoxcpm().getServiceUrl()));
        tts.getOptions().put("voxcpm", ttsVoxcpm);

        // Translate
        SettingsResponse.ProviderGroup translate = data.getTranslate();
        translate.setCurrent(settingsRepository.get("translate.provider", appProperties.getTranslate().getProvider()));

        Map<String, String> translateOllama = new LinkedHashMap<>();
        translateOllama.put("baseUrl", settingsRepository.get("translate.ollama.baseUrl", appProperties.getTranslate().getOllama().getBaseUrl()));
        translateOllama.put("model", settingsRepository.get("translate.ollama.model", appProperties.getTranslate().getOllama().getModel()));
        translateOllama.put("concurrency", settingsRepository.get("translate.ollama.concurrency", String.valueOf(appProperties.getTranslate().getOllama().getConcurrency())));
        translate.getOptions().put("ollama", translateOllama);

        Map<String, String> translateOpenai = new LinkedHashMap<>();
        translateOpenai.put("chatUrl", settingsRepository.get("translate.openai.chatUrl", appProperties.getTranslate().getOpenai().getChatUrl()));
        String apiKey = settingsRepository.get("translate.openai.apiKey", appProperties.getTranslate().getOpenai().getApiKey());
        translateOpenai.put("hasApiKey", String.valueOf(apiKey != null && !apiKey.isBlank()));
        translateOpenai.put("model", settingsRepository.get("translate.openai.model", appProperties.getTranslate().getOpenai().getModel()));
        translateOpenai.put("concurrency", settingsRepository.get("translate.openai.concurrency", String.valueOf(appProperties.getTranslate().getOpenai().getConcurrency())));
        translate.getOptions().put("openai", translateOpenai);

        // Separate
        SettingsResponse.ProviderGroup separate = data.getSeparate();
        separate.setCurrent(settingsRepository.get("separate.provider", appProperties.getSeparate().getProvider()));

        separate.getOptions().put("ffmpeg-simple", new LinkedHashMap<>());

        Map<String, String> separateDemucs = new LinkedHashMap<>();
        separateDemucs.put("model", settingsRepository.get("separate.demucs.model", appProperties.getSeparate().getDemucs().getModel()));
        separate.getOptions().put("demucs", separateDemucs);

        Map<String, String> separateApi = new LinkedHashMap<>();
        separateApi.put("serviceUrl", settingsRepository.get("separate.audio-separator-api.serviceUrl", appProperties.getSeparate().getAudioSeparatorApi().getServiceUrl()));
        separate.getOptions().put("audio-separator-api", separateApi);

        return data;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
