package com.youdub.replica.service;

import com.youdub.replica.service.adapter.asr.SpeechRecognizer;
import com.youdub.replica.service.adapter.audio.AudioProcessor;
import com.youdub.replica.service.adapter.download.Downloader;
import com.youdub.replica.service.adapter.separate.SourceSeparator;
import com.youdub.replica.service.adapter.translate.Translator;
import com.youdub.replica.service.adapter.tts.TtsProvider;
import com.youdub.replica.service.adapter.video.VideoProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 适配器注册 E2E 测试。
 * 验证所有适配器实现都正确注册为 Spring Bean，并通过 Map<String, Interface> 注入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdapterE2ETest {

    @Autowired
    private Map<String, Downloader> downloaders;

    @Autowired
    private Map<String, SourceSeparator> separators;

    @Autowired
    private Map<String, SpeechRecognizer> recognizers;

    @Autowired
    private Map<String, Translator> translators;

    @Autowired
    private Map<String, TtsProvider> ttsProviders;

    @Autowired
    private Map<String, AudioProcessor> audioProcessors;

    @Autowired
    private Map<String, VideoProcessor> videoProcessors;

    @Test
    void downloaders_shouldBeRegistered() {
        assertNotNull(downloaders);
        assertTrue(downloaders.containsKey("ytdlp"));
        assertTrue(downloaders.containsKey("local"));
    }

    @Test
    void separators_shouldBeRegistered() {
        assertNotNull(separators);
        assertTrue(separators.containsKey("ffmpeg-simple"));
        assertTrue(separators.containsKey("demucs"));
        assertTrue(separators.containsKey("audio-separator-api"));
    }

    @Test
    void recognizers_shouldBeRegistered() {
        assertNotNull(recognizers);
        assertTrue(recognizers.containsKey("whisper-api"));
        assertTrue(recognizers.containsKey("whisper-cpp"));
    }

    @Test
    void translators_shouldBeRegistered() {
        assertNotNull(translators);
        assertTrue(translators.containsKey("openai"));
        assertTrue(translators.containsKey("ollama"));
    }

    @Test
    void ttsProviders_shouldBeRegistered() {
        assertNotNull(ttsProviders);
        assertTrue(ttsProviders.containsKey("voxcpm"));
        assertTrue(ttsProviders.containsKey("edge-tts"));
        assertTrue(ttsProviders.containsKey("openai-tts"));
    }

    @Test
    void audioProcessors_shouldBeRegistered() {
        assertNotNull(audioProcessors);
        assertTrue(audioProcessors.containsKey("ffmpeg-audio"));
    }

    @Test
    void videoProcessors_shouldBeRegistered() {
        assertNotNull(videoProcessors);
        assertTrue(videoProcessors.containsKey("ffmpeg-video"));
    }
}
