package com.youdub.replica.controller;

import com.youdub.replica.dto.SettingsRequest;
import com.youdub.replica.dto.SettingsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设置管理 E2E 测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsControllerE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void getSettings_shouldReturnDefaults() {
        ResponseEntity<SettingsResponse> resp = restTemplate.getForEntity("/api/settings", SettingsResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().getOpenai());
        assertNotNull(resp.getBody().getYtdlp());
        assertNotNull(resp.getBody().getYoutubeCookie());
        assertEquals("", resp.getBody().getYoutubeCookie().getContent());
    }

    @Test
    void saveSettings_shouldPersistAndReturn() {
        SettingsRequest request = new SettingsRequest();
        SettingsRequest.OpenAiSettings openai = new SettingsRequest.OpenAiSettings();
        openai.setBaseUrl("https://api.example.com/v1");
        openai.setApiKey("sk-test-key-1234567890abcdef");
        openai.setModel("gpt-4o");
        openai.setTranslateConcurrency(100);
        request.setOpenai(openai);

        SettingsRequest.YtdlpSettings ytdlp = new SettingsRequest.YtdlpSettings();
        ytdlp.setProxyPort("7890");
        request.setYtdlp(ytdlp);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SettingsRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<SettingsResponse> resp = restTemplate.postForEntity("/api/settings", entity, SettingsResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("https://api.example.com/v1", resp.getBody().getOpenai().getBaseUrl());
        assertTrue(resp.getBody().getOpenai().isHasApiKey());
        assertTrue(resp.getBody().getOpenai().getApiKey().contains("***"));
        assertEquals("gpt-4o", resp.getBody().getOpenai().getModel());
        assertEquals(100, resp.getBody().getOpenai().getTranslateConcurrency());
        assertEquals("7890", resp.getBody().getYtdlp().getProxyPort());
    }

    @Test
    void saveSettings_clearApiKey_shouldRemoveKey() {
        // 先保存一个 key
        SettingsRequest saveReq = new SettingsRequest();
        SettingsRequest.OpenAiSettings openai = new SettingsRequest.OpenAiSettings();
        openai.setApiKey("sk-to-be-cleared-123456");
        saveReq.setOpenai(openai);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SettingsRequest> saveEntity = new HttpEntity<>(saveReq, headers);
        restTemplate.postForEntity("/api/settings", saveEntity, SettingsResponse.class);

        // 清除 key
        SettingsRequest clearReq = new SettingsRequest();
        SettingsRequest.OpenAiSettings clearOpenai = new SettingsRequest.OpenAiSettings();
        clearOpenai.setClearApiKey(true);
        clearReq.setOpenai(clearOpenai);
        HttpEntity<SettingsRequest> clearEntity = new HttpEntity<>(clearReq, headers);

        ResponseEntity<SettingsResponse> resp = restTemplate.postForEntity("/api/settings", clearEntity, SettingsResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertFalse(resp.getBody().getOpenai().isHasApiKey());
    }

    @Test
    void saveSettings_youtubeCookie_shouldSaveFile() {
        SettingsRequest request = new SettingsRequest();
        SettingsRequest.YoutubeCookieSettings cookie = new SettingsRequest.YoutubeCookieSettings();
        cookie.setContent("# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tFALSE\t0\tname\tvalue\n");
        request.setYoutubeCookie(cookie);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SettingsRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<SettingsResponse> resp = restTemplate.postForEntity("/api/settings", entity, SettingsResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().getYoutubeCookie().isExists());
        assertTrue(resp.getBody().getYoutubeCookie().getSize() > 0);
        assertEquals("", resp.getBody().getYoutubeCookie().getContent());
    }

    @Test
    void listOpenAiModels_withoutApiKey_shouldReturn400() {
        // 先清除 API key
        SettingsRequest clearReq = new SettingsRequest();
        SettingsRequest.OpenAiSettings clearOpenai = new SettingsRequest.OpenAiSettings();
        clearOpenai.setClearApiKey(true);
        clearReq.setOpenai(clearOpenai);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SettingsRequest> clearEntity = new HttpEntity<>(clearReq, headers);
        restTemplate.postForEntity("/api/settings", clearEntity, SettingsResponse.class);

        // 请求模型列表（不提供 apiKey）
        com.youdub.replica.dto.OpenAiModelsRequest modelsReq = new com.youdub.replica.dto.OpenAiModelsRequest();
        modelsReq.setBaseUrl("https://api.openai.com/v1");
        modelsReq.setApiKey("");
        HttpEntity<com.youdub.replica.dto.OpenAiModelsRequest> entity = new HttpEntity<>(modelsReq, headers);

        ResponseEntity<java.util.Map> resp = restTemplate.postForEntity("/api/settings/openai/models", entity, java.util.Map.class);

        assertEquals(400, resp.getStatusCode().value());
    }
}
