package com.youdub.replica.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youdub.replica.dto.OpenAiModelsRequest;
import com.youdub.replica.dto.SettingsRequest;
import com.youdub.replica.dto.SettingsResponse;
import com.youdub.replica.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 设置管理接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public SettingsResponse getSettings() {
        return settingsService.getSettings();
    }

    @PostMapping
    public SettingsResponse saveSettings(@RequestBody SettingsRequest request) {
        return settingsService.saveSettings(request);
    }

    @PostMapping("/openai/models")
    public Map<String, List<String>> listOpenAiModels(@RequestBody OpenAiModelsRequest request) {
        List<String> models = settingsService.getOpenAiModels(request.getBaseUrl(), request.getApiKey());
        return Map.of("models", models);
    }

    @GetMapping("/edge-tts/voices")
    public Map<String, List<String>> listEdgeTtsVoices() {
        List<String> voices = settingsService.getEdgeTtsVoices();
        return Map.of("voices", voices);
    }
}
