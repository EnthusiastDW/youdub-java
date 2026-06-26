package com.youdub.replica.service;

import com.youdub.replica.dto.SettingsRequest;
import com.youdub.replica.dto.SettingsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SettingsService E2E 测试。
 * 验证设置的读取、保存、Cookie 管理等功能。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsServiceE2ETest {

    @Autowired
    private SettingsService settingsService;

    @Test
    void getSettings_shouldReturnDefaults() {
        SettingsResponse resp = settingsService.getSettings();

        assertNotNull(resp);
        assertNotNull(resp.getOpenai());
        assertNotNull(resp.getYtdlp());
        assertNotNull(resp.getYoutubeCookie());
        assertEquals("", resp.getYoutubeCookie().getContent());
    }

    @Test
    void saveSettings_shouldPersistOpenAiSettings() {
        SettingsRequest request = new SettingsRequest();
        SettingsRequest.OpenAiSettings openai = new SettingsRequest.OpenAiSettings();
        openai.setBaseUrl("https://api.example.com/v1");
        openai.setApiKey("sk-test-key-1234567890abcdef");
        openai.setModel("gpt-4o");
        openai.setTranslateConcurrency(100);
        request.setOpenai(openai);

        SettingsResponse resp = settingsService.saveSettings(request);

        assertNotNull(resp);
        assertEquals("https://api.example.com/v1", resp.getOpenai().getBaseUrl());
        assertTrue(resp.getOpenai().isHasApiKey());
        assertTrue(resp.getOpenai().getApiKey().contains("***"));
        assertEquals("gpt-4o", resp.getOpenai().getModel());
        assertEquals(100, resp.getOpenai().getTranslateConcurrency());
    }

    @Test
    void saveSettings_clearApiKey_shouldRemoveKey() {
        // 先保存一个 key
        SettingsRequest saveReq = new SettingsRequest();
        SettingsRequest.OpenAiSettings openai = new SettingsRequest.OpenAiSettings();
        openai.setApiKey("sk-to-be-cleared-123456");
        saveReq.setOpenai(openai);
        settingsService.saveSettings(saveReq);

        // 清除 key
        SettingsRequest clearReq = new SettingsRequest();
        SettingsRequest.OpenAiSettings clearOpenai = new SettingsRequest.OpenAiSettings();
        clearOpenai.setClearApiKey(true);
        clearReq.setOpenai(clearOpenai);

        SettingsResponse resp = settingsService.saveSettings(clearReq);

        assertFalse(resp.getOpenai().isHasApiKey());
    }

    @Test
    void saveSettings_ytdlpProxy_shouldPersist() {
        SettingsRequest request = new SettingsRequest();
        SettingsRequest.YtdlpSettings ytdlp = new SettingsRequest.YtdlpSettings();
        ytdlp.setProxyPort("7890");
        request.setYtdlp(ytdlp);

        SettingsResponse resp = settingsService.saveSettings(request);

        assertEquals("7890", resp.getYtdlp().getProxyPort());
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
        // 先清除 API key
        SettingsRequest clearReq = new SettingsRequest();
        SettingsRequest.OpenAiSettings clearOpenai = new SettingsRequest.OpenAiSettings();
        clearOpenai.setClearApiKey(true);
        clearReq.setOpenai(clearOpenai);
        settingsService.saveSettings(clearReq);

        // 请求模型列表（不提供 apiKey）
        assertThrows(IllegalArgumentException.class, () ->
                settingsService.getOpenAiModels("https://api.openai.com/v1", ""));
    }
}
