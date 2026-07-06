package com.youdub.replica.dto;

import lombok.Data;

import java.util.Map;

/**
 * 设置保存请求。
 */
@Data
public class SettingsRequest {

    private YtdlpSettings ytdlp = new YtdlpSettings();
    private YoutubeCookieSettings youtubeCookie = new YoutubeCookieSettings();
    private ProviderSelections providers;
    /** Provider-specific config overrides（顶层，与前端发送结构一致） */
    private Map<String, String> providerConfigs;
    private String notesTemplate;

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
        private String asrCorrector;
        /** Provider-specific config overrides: key = "step.provider.field", value = "..." */
        private Map<String, String> providerConfigs;
    }
}
