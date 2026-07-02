package com.youdub.replica.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为每个适配器注册独立的配置 bean。
 * 返回 AppProperties 中对应的嵌套实例，确保 ConfigInitializer.mergeFromDb
 * 对 AppProperties 的修改能同步到所有适配器实际使用的配置对象。
 */
@Configuration
@RequiredArgsConstructor
public class AdapterConfig {

    private final AppProperties appProperties;

    @Bean
    @ConfigurationProperties(prefix = "app.asr.whisper-cpp")
    public AppProperties.Asr.WhisperCpp whisperCppConfig() {
        return appProperties.getAsr().getWhisperCpp();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.tts.edge-tts")
    public AppProperties.Tts.EdgeTts edgeTtsConfig() {
        return appProperties.getTts().getEdgeTts();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.tts.voxcpm")
    public AppProperties.Tts.Voxcpm voxcpmConfig() {
        return appProperties.getTts().getVoxcpm();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.separate.demucs")
    public AppProperties.Separate.Demucs demucsConfig() {
        return appProperties.getSeparate().getDemucs();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.separate.audio-separator-api")
    public AppProperties.Separate.AudioSeparatorApi audioSeparatorApiConfig() {
        return appProperties.getSeparate().getAudioSeparatorApi();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.asr.whisper-api")
    public AppProperties.Asr.WhisperApi whisperApiConfig() {
        return appProperties.getAsr().getWhisperApi();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.tts.openai-tts")
    public AppProperties.Tts.OpenaiTts openaiTtsConfig() {
        return appProperties.getTts().getOpenaiTts();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.ffmpeg")
    public AppProperties.Ffmpeg ffmpegConfig() {
        return appProperties.getFfmpeg();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.download")
    public AppProperties.Download downloadConfig() {
        return appProperties.getDownload();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.translate.openai")
    public AppProperties.Translate.Openai openaiTranslateConfig() {
        return appProperties.getTranslate().getOpenai();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.translate.ollama")
    public AppProperties.Translate.Ollama ollamaConfig() {
        return appProperties.getTranslate().getOllama();
    }
}
