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
import org.springframework.core.env.Environment;

/**
 * 设置服务。
 * 管理应用设置（OpenAI、yt-dlp 代理、YouTube cookie 等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final String KEY_OPENAI_BASE_URL = "openai.baseUrl";
    private static final String KEY_OPENAI_API_KEY = "openai.apiKey";
    private static final String KEY_OPENAI_MODEL = "openai.model";
    private static final String KEY_OPENAI_CONCURRENCY = "openai.translateConcurrency";
    private static final String KEY_YTDLP_PROXY_PORT = "ytdlp.proxyPort";
    private static final String YOUTUBE_COOKIE_FILE = "youtube.txt";

    private final SettingsRepository settingsRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Environment environment;

    /**
     * 获取所有设置（从 DB + 环境变量合并）。
     */
    public SettingsResponse getSettings() {
        SettingsResponse resp = new SettingsResponse();

        SettingsResponse.OpenAiSettings openai = new SettingsResponse.OpenAiSettings();
        openai.setBaseUrl(settingsRepository.get(KEY_OPENAI_BASE_URL, appProperties.getOpenai().getBaseUrl()));
        String apiKey = settingsRepository.get(KEY_OPENAI_API_KEY, appProperties.getOpenai().getApiKey());
        openai.setHasApiKey(apiKey != null && !apiKey.isBlank());
        openai.setApiKey(maskApiKey(apiKey));
        openai.setModel(settingsRepository.get(KEY_OPENAI_MODEL, appProperties.getOpenai().getModel()));
        String concurrencyStr = settingsRepository.get(KEY_OPENAI_CONCURRENCY, "50");
        try {
            openai.setTranslateConcurrency(Integer.parseInt(concurrencyStr));
        } catch (NumberFormatException e) {
            openai.setTranslateConcurrency(50);
        }
        resp.setOpenai(openai);

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
        if (request.getOpenai() != null) {
            SettingsRequest.OpenAiSettings openai = request.getOpenai();
            if (openai.getBaseUrl() != null) {
                settingsRepository.set(KEY_OPENAI_BASE_URL, openai.getBaseUrl());
                appProperties.getOpenai().setBaseUrl(openai.getBaseUrl());
            }
            if (openai.isClearApiKey()) {
                settingsRepository.set(KEY_OPENAI_API_KEY, "");
                appProperties.getOpenai().setApiKey("");
            } else if (openai.getApiKey() != null && !openai.getApiKey().isBlank()) {
                settingsRepository.set(KEY_OPENAI_API_KEY, openai.getApiKey());
                appProperties.getOpenai().setApiKey(openai.getApiKey());
            }
            if (openai.getModel() != null) {
                settingsRepository.set(KEY_OPENAI_MODEL, openai.getModel());
                appProperties.getOpenai().setModel(openai.getModel());
            }
            if (openai.getTranslateConcurrency() != null) {
                settingsRepository.set(KEY_OPENAI_CONCURRENCY, String.valueOf(openai.getTranslateConcurrency()));
            }
        }

        if (request.getYtdlp() != null && request.getYtdlp().getProxy() != null) {
            settingsRepository.set("ytdlp.proxy", request.getYtdlp().getProxy());
        }

        if (request.getYoutubeCookie() != null) {
            saveYouTubeCookie(request.getYoutubeCookie().getContent());
        }

        if (request.getProviders() != null) {
            SettingsRequest.ProviderSelections p = request.getProviders();
            if (p.getAsr() != null) {
                settingsRepository.set("asr.provider", p.getAsr());
            }
            if (p.getTts() != null) {
                settingsRepository.set("tts.provider", p.getTts());
            }
            if (p.getTranslate() != null) {
                settingsRepository.set("translate.provider", p.getTranslate());
            }
            if (p.getSeparate() != null) {
                settingsRepository.set("separate.provider", p.getSeparate());
            }
        }

        return getSettings();
    }

    /**
     * 调用 OpenAI /models API 获取模型列表。
     */
    public List<String> getOpenAiModels(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = settingsRepository.get(KEY_OPENAI_BASE_URL, appProperties.getOpenai().getBaseUrl());
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = settingsRepository.get(KEY_OPENAI_API_KEY, appProperties.getOpenai().getApiKey());
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
     * 从 YAML / 环境加载所有 provider 配置。
     */
    private SettingsResponse.ProvidersData buildProvidersData() {
        SettingsResponse.ProvidersData data = new SettingsResponse.ProvidersData();

        // ASR
        SettingsResponse.ProviderGroup asr = data.getAsr();
        asr.setCurrent(settingsRepository.get("asr.provider", appProperties.getAsr().getProvider()));
        Map<String, String> whisperOpenai = new LinkedHashMap<>();
        whisperOpenai.put("url", environment.getProperty("whisper.openai.url", ""));
        whisperOpenai.put("apiKey", environment.getProperty("whisper.openai.api-key", ""));
        whisperOpenai.put("model", environment.getProperty("whisper.openai.model", ""));
        asr.getOptions().put("whisper-api", whisperOpenai);
        Map<String, String> whisperCpp = new LinkedHashMap<>();
        whisperCpp.put("model", environment.getProperty("whisper.cpp.model", ""));
        asr.getOptions().put("whisper-cpp", whisperCpp);

        // TTS
        SettingsResponse.ProviderGroup tts = data.getTts();
        tts.setCurrent(settingsRepository.get("tts.provider", appProperties.getTts().getProvider()));
        Map<String, String> ttsEdge = new LinkedHashMap<>();
        ttsEdge.put("path", environment.getProperty("tts.edge.path", ""));
        ttsEdge.put("voice", environment.getProperty("tts.edge.voice", ""));
        tts.getOptions().put("edge-tts", ttsEdge);
        Map<String, String> ttsOpenApi = new LinkedHashMap<>();
        ttsOpenApi.put("url", environment.getProperty("tts.open-api.url", ""));
        ttsOpenApi.put("apiKey", environment.getProperty("tts.open-api.api-key", ""));
        ttsOpenApi.put("model", environment.getProperty("tts.open-api.model", ""));
        ttsOpenApi.put("voice", environment.getProperty("tts.open-api.voice", ""));
        tts.getOptions().put("openai-tts", ttsOpenApi);
        Map<String, String> ttsVoxcpm = new LinkedHashMap<>();
        ttsVoxcpm.put("serviceUrl", environment.getProperty("tts.voxcpm.service-url", ""));
        tts.getOptions().put("voxcpm", ttsVoxcpm);

        // Translate
        SettingsResponse.ProviderGroup translate = data.getTranslate();
        translate.setCurrent(settingsRepository.get("translate.provider", appProperties.getTranslate().getProvider()));
        Map<String, String> translateOllama = new LinkedHashMap<>();
        translateOllama.put("url", environment.getProperty("translate.ollama.url", ""));
        translateOllama.put("model", environment.getProperty("translate.ollama.model", ""));
        translateOllama.put("concurrency", environment.getProperty("translate.ollama.concurrency", "1"));
        translate.getOptions().put("local-llm", translateOllama);
        Map<String, String> translateOpenai = new LinkedHashMap<>();
        translateOpenai.put("url", environment.getProperty("translate.openai.url", ""));
        String apiKeyVal = environment.getProperty("translate.openai.api-key", "");
        translateOpenai.put("hasApiKey", String.valueOf(apiKeyVal != null && !apiKeyVal.isBlank()));
        translateOpenai.put("model", environment.getProperty("translate.openai.model", ""));
        translateOpenai.put("concurrency", environment.getProperty("translate.openai.concurrency", "50"));
        translate.getOptions().put("openai", translateOpenai);

        // Separate
        SettingsResponse.ProviderGroup separate = data.getSeparate();
        separate.setCurrent(settingsRepository.get("separate.provider", appProperties.getSeparate().getProvider()));
        Map<String, String> separateCfg = new LinkedHashMap<>();
        separateCfg.put("model", environment.getProperty("app.separate.model", ""));
        separate.getOptions().put(appProperties.getSeparate().getProvider(), separateCfg);

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
