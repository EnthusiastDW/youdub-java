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

import java.util.Map;

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
        assertNotNull(resp.getBody().getYtdlp());
        assertNotNull(resp.getBody().getYoutubeCookie());
        assertEquals("", resp.getBody().getYoutubeCookie().getContent());
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
    void saveSettings_providerConfigs_shouldPersist() {
        SettingsRequest request = new SettingsRequest();
        SettingsRequest.ProviderSelections providers = new SettingsRequest.ProviderSelections();
        providers.setTranslate("openai");
        providers.setAsr("whisper-api");
        providers.setTts("edge-tts");
        providers.setSeparate("demucs");
        Map<String, String> configs = new java.util.HashMap<>();
        configs.put("translate.openai.chatUrl", "https://api.example.com/v1/chat/completions");
        configs.put("translate.openai.apiKey", "api-key-from-ui");
        configs.put("asr.whisper-api.baseUrl", "https://whisper.example.com");
        configs.put("tts.edge-tts.voice", "zh-CN-XiaoxiaoNeural");
        providers.setProviderConfigs(configs);
        request.setProviders(providers);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SettingsRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<SettingsResponse> resp = restTemplate.postForEntity("/api/settings", entity, SettingsResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("openai", resp.getBody().getProviders().getTranslate().getCurrent());
        assertEquals("https://api.example.com/v1/chat/completions", resp.getBody().getProviders().getTranslate().getOptions().get("openai").get("chatUrl"));
        assertEquals("https://whisper.example.com", resp.getBody().getProviders().getAsr().getOptions().get("whisper-api").get("baseUrl"));
        assertEquals("zh-CN-XiaoxiaoNeural", resp.getBody().getProviders().getTts().getOptions().get("edge-tts").get("voice"));
        assertEquals("true", resp.getBody().getProviders().getTranslate().getOptions().get("openai").get("hasApiKey"));
    }

    @Test
    void listOpenAiModels_withoutApiKey_shouldReturn400() {
        com.youdub.replica.dto.OpenAiModelsRequest modelsReq = new com.youdub.replica.dto.OpenAiModelsRequest();
        modelsReq.setBaseUrl("https://api.openai.com/v1");
        modelsReq.setApiKey("");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<com.youdub.replica.dto.OpenAiModelsRequest> entity = new HttpEntity<>(modelsReq, headers);

        ResponseEntity<java.util.Map> resp = restTemplate.postForEntity("/api/settings/openai/models", entity, java.util.Map.class);

        assertEquals(400, resp.getStatusCode().value());
    }
}
