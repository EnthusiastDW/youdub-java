package com.youdub.replica.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置响应。
 * API key 脱敏显示，cookie 内容始终为空（仅返回元信息）。
 */
@Data
public class SettingsResponse {

    private YtdlpSettings ytdlp = new YtdlpSettings();
    private YoutubeCookieInfo youtubeCookie = new YoutubeCookieInfo();
    private ProvidersData providers = new ProvidersData();
    private String notesTemplate = "";

    @Data
    public static class YtdlpSettings {
        private String proxy;
    }

    @Data
    public static class YoutubeCookieInfo {
        private boolean exists;
        private long size;
        private String updatedAt;
        /** 始终为空，不向前端返回 cookie 内容 */
        private String content = "";
    }

    @Data
    public static class ProvidersData {
        private ProviderGroup asr = new ProviderGroup();
        private ProviderGroup tts = new ProviderGroup();
        private ProviderGroup translate = new ProviderGroup();
        private ProviderGroup separate = new ProviderGroup();
    }

    @Data
    public static class ProviderGroup {
        /** 当前选中的 provider key */
        private String current;
        /** 各 provider 选项的配置参数 */
        private Map<String, Map<String, String>> options = new LinkedHashMap<>();
    }
}
