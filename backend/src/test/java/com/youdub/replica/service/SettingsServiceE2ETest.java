package com.youdub.replica.service;

import com.youdub.replica.config.AppProperties;
import com.youdub.replica.dto.SettingsRequest;
import com.youdub.replica.dto.SettingsResponse;
import com.youdub.replica.repository.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SettingsService E2E 测试。
 * 验证设置的读取、保存、Cookie 管理等功能。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsServiceE2ETest {

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private SettingsRepository settingsRepository;

    @Test
    void getSettings_shouldReturnDefaults() {
        SettingsResponse resp = settingsService.getSettings();

        assertNotNull(resp);
        assertNotNull(resp.getYtdlp());
        assertNotNull(resp.getYoutubeCookie());
        assertEquals("", resp.getYoutubeCookie().getContent());
    }

    @Test
    void saveSettings_ytdlpProxy_shouldPersist() {
        SettingsRequest request = new SettingsRequest();
        SettingsRequest.YtdlpSettings ytdlp = new SettingsRequest.YtdlpSettings();
        ytdlp.setProxy("http://127.0.0.1:7890");
        request.setYtdlp(ytdlp);

        SettingsResponse resp = settingsService.saveSettings(request);

        assertEquals("http://127.0.0.1:7890", resp.getYtdlp().getProxy());
    }

    @Test
    void saveSettings_providerConfigs_shouldPersist() {
        SettingsRequest request = new SettingsRequest();
        SettingsRequest.ProviderSelections providers = new SettingsRequest.ProviderSelections();
        providers.setTranslate("openai");
        providers.setAsr("whisper-api");
        providers.setTts("edge-tts");
        providers.setSeparate("demucs");
        Map<String, String> configs = new HashMap<>();
        configs.put("translate.openai.chatUrl", "https://saved.example.com/v1/chat/completions");
        configs.put("translate.openai.apiKey", "saved-key");
        configs.put("asr.whisper-api.baseUrl", "https://saved-whisper.example.com");
        configs.put("tts.edge-tts.voice", "saved-voice");
        providers.setProviderConfigs(configs);
        request.setProviders(providers);

        SettingsResponse resp = settingsService.saveSettings(request);

        assertEquals("openai", resp.getProviders().getTranslate().getCurrent());
        assertEquals("https://saved.example.com/v1/chat/completions", resp.getProviders().getTranslate().getOptions().get("openai").get("chatUrl"));
        assertEquals("https://saved-whisper.example.com", resp.getProviders().getAsr().getOptions().get("whisper-api").get("baseUrl"));
        assertEquals("saved-voice", resp.getProviders().getTts().getOptions().get("edge-tts").get("voice"));
        // API key 脱敏返回，不直接比较原值
        assertEquals("true", resp.getProviders().getTranslate().getOptions().get("openai").get("hasApiKey"));
    }

    @Test
    void mergeFromDb_shouldOverrideAppPropertiesAndAdapterConfigs() {
        // 先保存配置到 DB
        SettingsRequest request = new SettingsRequest();
        SettingsRequest.ProviderSelections providers = new SettingsRequest.ProviderSelections();
        providers.setTranslate("openai");
        Map<String, String> configs = new HashMap<>();
        configs.put("translate.openai.chatUrl", "https://db.example.com/v1/chat/completions");
        configs.put("translate.openai.apiKey", "db-api-key");
        configs.put("translate.openai.model", "db-model");
        configs.put("translate.openai.concurrency", "7");
        configs.put("asr.whisper-api.baseUrl", "https://db-whisper.example.com");
        configs.put("tts.edge-tts.voice", "db-voice");
        configs.put("separate.demucs.model", "db-demucs");
        configs.put("ytdlp.proxy", "http://db.proxy:7890");
        providers.setProviderConfigs(configs);
        request.setProviders(providers);
        settingsService.saveSettings(request);

        // 重新触发 DB -> AppProperties 合并（模拟重启）
        appProperties.mergeFromDb(settingsRepository);

        // 验证 AppProperties 被覆盖
        assertEquals("https://db.example.com/v1/chat/completions", appProperties.getTranslate().getOpenai().getChatUrl());
        assertEquals("db-api-key", appProperties.getTranslate().getOpenai().getApiKey());
        assertEquals("db-model", appProperties.getTranslate().getOpenai().getModel());
        assertEquals(7, appProperties.getTranslate().getOpenai().getConcurrency());
        assertEquals("https://db-whisper.example.com", appProperties.getAsr().getWhisperApi().getBaseUrl());
        assertEquals("db-voice", appProperties.getTts().getEdgeTts().getVoice());
        assertEquals("db-demucs", appProperties.getSeparate().getDemucs().getModel());
        assertEquals("http://db.proxy:7890", appProperties.getYtdlp().getProxy());
    }

    @Test
    void saveYouTubeCookie_shouldSaveFile() {
        String cookieContent = "# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tFALSE\t0\tname\tvalue\n";
        settingsService.saveYouTubeCookie(cookieContent);

        SettingsResponse resp = settingsService.getSettings();
        assertTrue(resp.getYoutubeCookie().isExists());
        assertTrue(resp.getYoutubeCookie().getSize() > 0);
        assertEquals("", resp.getYoutubeCookie().getContent());
    }

    @Test
    void saveYouTubeCookie_emptyContent_shouldDeleteFile() {
        // 先保存
        settingsService.saveYouTubeCookie("some cookie content");
        // 再清除
        settingsService.saveYouTubeCookie("");

        SettingsResponse resp = settingsService.getSettings();
        assertFalse(resp.getYoutubeCookie().isExists());
    }

    @Test
    void getYouTubeCookiePath_shouldReturnPath() {
        String path = settingsService.getYouTubeCookiePath();
        assertNotNull(path);
        assertTrue(path.contains("youtube.txt"));
    }

    @Test
    void getOpenAiModels_withoutApiKey_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                settingsService.getOpenAiModels("https://api.openai.com/v1", ""));
    }
}
