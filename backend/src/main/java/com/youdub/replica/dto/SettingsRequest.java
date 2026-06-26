package com.youdub.replica.dto;

import lombok.Data;

/**
 * 设置保存请求。
 */
@Data
public class SettingsRequest {

    private OpenAiSettings openai = new OpenAiSettings();
    private YtdlpSettings ytdlp = new YtdlpSettings();
    private YoutubeCookieSettings youtubeCookie = new YoutubeCookieSettings();
    private ProviderSelections providers;

    @Data
    public static class OpenAiSettings {
        private String baseUrl;
        private String apiKey;
        /** 是否清除已保存的 API key */
        private boolean clearApiKey;
        private String model;
        private Integer translateConcurrency;
    }

    @Data
    public static class YtdlpSettings {
        private String proxy;
    }

    @Data
    public static class YoutubeCookieSettings {
        /** Netscape 格式 cookie 文本内容 */
        private String content;
    }

    @Data
    public static class ProviderSelections {
        private String asr;
        private String tts;
        private String translate;
        private String separate;
    }
}
