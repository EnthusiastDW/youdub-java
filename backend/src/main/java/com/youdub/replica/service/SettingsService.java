package com.youdub.replica.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.dto.SettingsRequest;
import com.youdub.replica.dto.SettingsResponse;
import com.youdub.replica.repository.SettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandResult;
import com.youdub.replica.util.CommandRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.youdub.replica.service.adapter.AdapterConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final String YOUTUBE_COOKIE_FILE = "youtube.txt";

    /**
     * 映射: providerName → 其所属 category
     * providerName 与 @Component("...") 中的名称一致
     */
    private static final Map<String, String> PROVIDER_CATEGORY = new HashMap<>();
    static {
        PROVIDER_CATEGORY.put(WHISPER_API, "asr");
        PROVIDER_CATEGORY.put(WHISPER_CPP, "asr");
        PROVIDER_CATEGORY.put(EDGE_TTS, "tts");
        PROVIDER_CATEGORY.put(VOXCPM, "tts");
        PROVIDER_CATEGORY.put(OPENAI_TTS, "tts");
        PROVIDER_CATEGORY.put(OPENAI, "translate");
        PROVIDER_CATEGORY.put(OLLAMA, "translate");
        PROVIDER_CATEGORY.put(FFMPEG_SIMPLE, "separate");
        PROVIDER_CATEGORY.put(DEMUCS, "separate");
        PROVIDER_CATEGORY.put(AUDIO_SEPARATOR_API, "separate");
    }

    /**
     * 映射: providerName → AppProperties 中对应默认值的 getter
     */
    private static final Map<String, Function<AppProperties, Object>> DEFAULT_GETTER = new HashMap<>();
    static {
        DEFAULT_GETTER.put(WHISPER_API, ap -> ap.getAsr().getWhisperApi());
        DEFAULT_GETTER.put(WHISPER_CPP, ap -> ap.getAsr().getWhisperCpp());
        DEFAULT_GETTER.put(EDGE_TTS, ap -> ap.getTts().getEdgeTts());
        DEFAULT_GETTER.put(VOXCPM, ap -> ap.getTts().getVoxcpm());
        DEFAULT_GETTER.put(OPENAI_TTS, ap -> ap.getTts().getOpenaiTts());
        DEFAULT_GETTER.put(OPENAI, ap -> ap.getTranslate().getOpenai());
        DEFAULT_GETTER.put(OLLAMA, ap -> ap.getTranslate().getOllama());
        DEFAULT_GETTER.put(FFMPEG_SIMPLE, ap -> ap.getSeparate().getFfmpegSimple());
        DEFAULT_GETTER.put(DEMUCS, ap -> ap.getSeparate().getDemucs());
        DEFAULT_GETTER.put(AUDIO_SEPARATOR_API, ap -> ap.getSeparate().getAudioSeparatorApi());
    }

    /**
     * 全局配置类别列表 (key = 对应的 settings key)
     */
    private static final Map<String, Function<AppProperties, Object>> GLOBAL_CATEGORIES = new LinkedHashMap<>();
    static {
        GLOBAL_CATEGORIES.put("download", ap -> ap.getDownload());
        GLOBAL_CATEGORIES.put("ffmpeg", ap -> ap.getFfmpeg());
        GLOBAL_CATEGORIES.put("device", ap -> ap.getDeviceConfig());
    }

    private final SettingsRepository settingsRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * 获取 provider 的完整配置，反序列化为指定类型。
     * 合并策略: DB JSON blob → AppProperties 默认值。
     * DB 中存在的字段覆盖默认值，DB 中缺失的字段回退到 application.yml / 环境变量。
     * 不会返回 null — DB 无数据时返回 AppProperties 默认实例。
     *
     * @param providerName 与 @Component("...") 一致的名称
     * @param configType   配置 POJO 类型（如 AppProperties.Tts.Voxcpm.class）
     */
    @SuppressWarnings("unchecked")
    public <T> T getProviderConfig(String providerName, Class<T> configType) {
        String category = PROVIDER_CATEGORY.get(providerName);
        if (category == null) {
            throw new IllegalArgumentException("未知 provider: " + providerName);
        }
        String key = category + "." + providerName;

        T merged = deepCloneDefaults(providerName, configType);
        if (merged == null) {
            try {
                merged = configType.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("无法创建 " + configType.getSimpleName() + " 的实例", e);
            }
        }

        String json = settingsRepository.get(key, null);
        if (json != null && !json.isBlank()) {
            try {
                JsonNode overrides = objectMapper.readTree(json);
                if (overrides.isObject()) {
                    ObjectNode clean = overrides.deepCopy();
                    List<String> emptyFields = new ArrayList<>();
                    clean.fieldNames().forEachRemaining(f -> {
                        JsonNode val = clean.get(f);
                        if (val.isTextual() && val.asText().isBlank()) {
                            emptyFields.add(f);
                        }
                    });
                    emptyFields.forEach(clean::remove);
                    if (clean.size() > 0) {
                        objectMapper.readerForUpdating(merged).readValue(clean);
                    }
                }
            } catch (Exception e) {
                log.warn("解析 {} 配置失败，使用默认值: {}", key, e.getMessage());
            }
        }

        return merged;
    }

    /**
     * 保存 provider 配置（整体替换 JSON blob）。
     */
    public void saveProviderConfig(String providerName, Object config) {
        String category = PROVIDER_CATEGORY.get(providerName);
        if (category == null) {
            throw new IllegalArgumentException("未知 provider: " + providerName);
        }
        String key = category + "." + providerName;
        try {
            String json = objectMapper.writeValueAsString(config);
            settingsRepository.set(key, json);
        } catch (Exception e) {
            throw new RuntimeException("保存 provider 配置失败: " + providerName, e);
        }
    }

    /**
     * 获取全局配置。
     *
     * @param category 类别名称，如 "download"、"ffmpeg"
     */
    @SuppressWarnings("unchecked")
    public <T> T getGlobalConfig(String category, Class<T> configType) {
        T merged = deepCloneGlobal(category, configType);
        if (merged == null) {
            try {
                merged = configType.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("无法创建 " + configType.getSimpleName() + " 的实例", e);
            }
        }

        String json = settingsRepository.get(category, null);
        if (json != null && !json.isBlank()) {
            try {
                objectMapper.readerForUpdating(merged).readValue(json);
            } catch (Exception e) {
                log.warn("解析 {} 全局配置失败，使用默认值: {}", category, e.getMessage());
            }
        }

        return merged;
    }

    /**
     * 保存全局配置（整体替换 JSON blob）。
     *
     * @param category 类别名称，如 "download"、"ffmpeg"
     * @param config   配置对象，将被序列化为 JSON 存储
     */
    public void saveGlobalConfig(String category, Object config) {
        try {
            String json = objectMapper.writeValueAsString(config);
            settingsRepository.set(category, json);
        } catch (Exception e) {
            throw new RuntimeException("保存全局配置失败: " + category, e);
        }
    }

    /**
     * 直接读取原始设置值。
     * 注意：仅用于兼容旧代码路径；新代码应优先使用 getProviderConfig / getGlobalConfig。
     */
    public String get(String key, String defaultValue) {
        return settingsRepository.get(key, defaultValue);
    }

    /**
     * 直接写入原始设置值。
     * 注意：仅用于兼容旧代码路径；新代码应优先使用 saveProviderConfig / saveGlobalConfig。
     */
    public void set(String key, String value) {
        settingsRepository.set(key, value);
    }

    /**
     * 获取当前所有设置（前端 Settings 页面使用）。
     * 返回合并了 DB 和默认值的完整设置视图。
     */
    public SettingsResponse getSettings() {
        SettingsResponse resp = new SettingsResponse();

        SettingsResponse.YtdlpSettings ytdlp = new SettingsResponse.YtdlpSettings();
        String proxy = Optional.ofNullable(getGlobalConfig("download", Map.class).get("proxy"))
                .map(Object::toString)
                .orElse(appProperties.getYtdlp().getProxy());
        ytdlp.setProxy(proxy);
        resp.setYtdlp(ytdlp);

        resp.setYoutubeCookie(buildCookieInfo());
        resp.setProviders(buildProvidersData());
        resp.setNotesTemplate(settingsRepository.get("notes_template", ""));
        return resp;
    }

    /**
     * 保存前端 Settings 页面提交的设置。
     * 包括：provider 选择、provider 配置（JSON blob）、下载代理、YouTube Cookie。
     */
    public SettingsResponse saveSettings(SettingsRequest request) {
        if (request.getProviders() != null) {
            SettingsRequest.ProviderSelections p = request.getProviders();
            if (p.getAsr() != null) settingsRepository.set("asr.provider", p.getAsr());
            if (p.getTts() != null) settingsRepository.set("tts.provider", p.getTts());
            if (p.getTranslate() != null) settingsRepository.set("translate.provider", p.getTranslate());
            if (p.getSeparate() != null) settingsRepository.set("separate.provider", p.getSeparate());
        }

        Map<String, String> configs = request.getProviderConfigs();
        if (configs == null && request.getProviders() != null) {
            configs = request.getProviders().getProviderConfigs();
        }
        if (configs != null) {
            Map<String, Map<String, String>> jsonBlobs = new HashMap<>();
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                String key = entry.getKey();
                // 平铺 key 格式: {category}.{provider}.{field}
                // 转为 JSON blob: {category}.{provider} = {"field": "value"}
                String[] parts = key.split("\\.", 3);
                if (parts.length == 3 && PROVIDER_CATEGORY.containsKey(parts[1])) {
                    jsonBlobs.computeIfAbsent(parts[0] + "." + parts[1], k -> new LinkedHashMap<>())
                            .put(parts[2], entry.getValue());
                } else {
                    settingsRepository.set(key, entry.getValue());
                }
            }
            for (Map.Entry<String, Map<String, String>> blob : jsonBlobs.entrySet()) {
                try {
                    String json = objectMapper.writeValueAsString(blob.getValue());
                    settingsRepository.set(blob.getKey(), json);
                } catch (Exception e) {
                    log.warn("保存配置 JSON blob 失败: {}", blob.getKey(), e);
                }
            }
        }

        if (request.getYtdlp() != null && request.getYtdlp().getProxy() != null) {
            try {
                String currentJson = settingsRepository.get("download", "{}");
                ObjectNode downloadNode = (ObjectNode) objectMapper.readTree(currentJson);
                downloadNode.put("proxy", request.getYtdlp().getProxy());
                settingsRepository.set("download", objectMapper.writeValueAsString(downloadNode));
            } catch (Exception e) {
                throw new RuntimeException("更新下载配置中的代理失败", e);
            }
        }

        if (request.getYoutubeCookie() != null) {
            saveYouTubeCookie(request.getYoutubeCookie().getContent());
        }

        if (request.getNotesTemplate() != null) {
            settingsRepository.set("notes_template", request.getNotesTemplate());
        }

        return getSettings();
    }

    /**
     * 获取 OpenAI 可用模型列表。
     * 当参数为 null/空时从 DB 或默认配置读取 baseUrl 和 apiKey。
     *
     * @throws IllegalArgumentException 当未配置 API key 时
     */
    public List<String> getOpenAiModels(String baseUrl, String apiKey) {
            var cfg = getProviderConfig(OPENAI, AppProperties.Translate.Openai.class);
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = normalizeBaseUrl(cfg.getChatUrl().replace("/chat/completions", ""));
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = cfg.getApiKey();
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
     * 获取 edge-tts 支持的 zh- 音色列表。
     * 通过子进程调用 edge-tts --list-voices，过滤中文音色。
     * edge-tts 未安装时返回空列表。
     */
    public List<String> getEdgeTtsVoices() {
            AppProperties.Tts.EdgeTts cfg = getProviderConfig(EDGE_TTS, AppProperties.Tts.EdgeTts.class);
        String edgePath = cfg.getPath();
        if (edgePath == null || edgePath.isBlank()) {
            edgePath = "edge-tts";
        }

        try {
            CommandResult result = CommandRunner.run(Command.builder()
                    .add(edgePath, "--list-voices")
                    .timeout(30_000)
                    .maxOutputLines(-1)
                    .throwOnNonZero(false)
                    .build());

            if (result.exitCode() != 0) {
                log.warn("edge-tts --list-voices 退出码非零：{}", result.exitCode());
                return List.of();
            }

            return result.lines().stream()
                    .skip(2)
                    .filter(line -> !line.isBlank() && !line.startsWith("---"))
                    .map(line -> line.split("\\s+")[0])
                    .filter(name -> name.startsWith("zh-"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("获取 edge-tts 音色列表失败（edge-tts 可能未安装）：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取 YouTube Cookie 文件路径。
     * 基于配置的 cookie 目录和文件名（youtube.txt）拼接。
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



    @SuppressWarnings("unchecked")
    private <T> T deepCloneDefaults(String providerName, Class<T> configType) {
        Function<AppProperties, Object> getter = DEFAULT_GETTER.get(providerName);
        if (getter == null) return null;

        Object defaults = getter.apply(appProperties);
        if (defaults == null) return null;

        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(defaults), configType);
        } catch (Exception e) {
            log.warn("克隆 {} 默认值失败: {}", providerName, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T deepCloneGlobal(String category, Class<T> configType) {
        Function<AppProperties, Object> getter = GLOBAL_CATEGORIES.get(category);
        if (getter == null) return null;

        Object defaults = getter.apply(appProperties);
        if (defaults == null) return null;

        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(defaults), configType);
        } catch (Exception e) {
            log.warn("克隆 {} 全局默认值失败: {}", category, e.getMessage());
            return null;
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

    private SettingsResponse.ProvidersData buildProvidersData() {
        SettingsResponse.ProvidersData data = new SettingsResponse.ProvidersData();

        buildProviderGroup(data.getAsr(), "asr");
        buildProviderGroup(data.getTts(), "tts");
        buildProviderGroup(data.getTranslate(), "translate");
        buildProviderGroup(data.getSeparate(), "separate");

        return data;
    }

    private void buildProviderGroup(SettingsResponse.ProviderGroup group, String category) {
        String defaultProvider = switch (category) {
            case "asr" -> appProperties.getAsr().getProvider();
            case "tts" -> appProperties.getTts().getProvider();
            case "translate" -> appProperties.getTranslate().getProvider();
            case "separate" -> appProperties.getSeparate().getProvider();
            default -> "";
        };
        group.setCurrent(settingsRepository.get(category + ".provider", defaultProvider));

        for (Map.Entry<String, String> entry : PROVIDER_CATEGORY.entrySet()) {
            if (!entry.getValue().equals(category)) continue;

            String providerName = entry.getKey();
            String key = category + "." + providerName;
            String json = settingsRepository.get(key, null);

            Map<String, String> opts = buildProviderOptionsWithDefaults(providerName);

            if (json != null && json.startsWith("{")) {
                try {
                    JsonNode node = objectMapper.readTree(json);
                    node.fieldNames().forEachRemaining(f -> {
                        JsonNode val = node.get(f);
                        opts.put(f, val.isTextual() ? val.asText() : val.toString());
                    });
                } catch (Exception e) {
                    log.warn("解析 {} 配置失败: {}", key, e.getMessage());
                }
            }
            // hasApiKey 是前端需要的计算字段，不存储在 DB 中
            String apiKey = opts.get("apiKey");
            if (apiKey != null && !apiKey.isBlank()) {
                opts.put("hasApiKey", "true");
            }

            group.getOptions().put(providerName, opts);
        }
    }

    private Map<String, String> buildProviderOptionsWithDefaults(String providerName) {
        Map<String, String> opts = new LinkedHashMap<>();
        Function<AppProperties, Object> getter = DEFAULT_GETTER.get(providerName);
        if (getter == null) return opts;

        Object defaults = getter.apply(appProperties);
        if (defaults == null) return opts;

        try {
            JsonNode defaultNode = objectMapper.valueToTree(defaults);
            if (defaultNode.isObject()) {
                defaultNode.fieldNames().forEachRemaining(f -> {
                    JsonNode val = defaultNode.get(f);
                    if (val.isTextual() && !val.asText().isBlank()) {
                        opts.put(f, val.asText());
                    } else if (val.isNumber()) {
                        opts.put(f, val.asText());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("序列化 {} 默认值失败: {}", providerName, e.getMessage());
        }
        return opts;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
