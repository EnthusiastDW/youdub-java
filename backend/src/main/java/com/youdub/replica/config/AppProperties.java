package com.youdub.replica.config;

import com.youdub.replica.repository.SettingsRepository;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String workfolder;
    private String dataDir;
    private String cookieDir;
    private String logDir;
    private String dbPath;
    private String device;
    private long uploadMaxBytes;
    private long subtitleMaxBytes;

    private Ytdlp ytdlp = new Ytdlp();
    private Asr asr = new Asr();
    private Tts tts = new Tts();
    private Translate translate = new Translate();
    private Separate separate = new Separate();
    private Audio audio = new Audio();
    private Ffmpeg ffmpeg = new Ffmpeg();
    private Download download = new Download();
    private Device deviceConfig = new Device();

    @Data
    public static class Ytdlp {
        private String proxy;
    }

    @Data
    public static class Asr {
        private String provider;
        private WhisperApi whisperApi = new WhisperApi();
        private WhisperCpp whisperCpp = new WhisperCpp();

        @Data
        public static class WhisperApi {
            private String baseUrl;
            private String url;
            private String apiKey;
            private String model;
        }

        @Data
        public static class WhisperCpp {
            private String model;
        }
    }

    @Data
    public static class Tts {
        private String provider;
        private EdgeTts edgeTts = new EdgeTts();
        private OpenaiTts openaiTts = new OpenaiTts();
        private Voxcpm voxcpm = new Voxcpm();

        @Data
        public static class EdgeTts {
            private String path;
            private String voice;
        }

        @Data
        public static class OpenaiTts {
            private String url;
            private String apiKey;
            private String model;
            private String voice;
        }

        @Data
        public static class Voxcpm {
            private String serviceUrl;
        }
    }

    @Data
    public static class Translate {
        private String provider;
        private Ollama ollama = new Ollama();
        private Openai openai = new Openai();

        @Data
        public static class Ollama {
            private String baseUrl;
            private String model;
            private int concurrency;
        }

        @Data
        public static class Openai {
            private String chatUrl;
            private String apiKey;
            private String model;
            private int concurrency;
        }
    }

    @Data
    public static class Separate {
        private String provider;
        private Demucs demucs = new Demucs();
        private AudioSeparatorApi audioSeparatorApi = new AudioSeparatorApi();

        @Data
        public static class Demucs {
            private String model;
        }

        @Data
        public static class AudioSeparatorApi {
            private String serviceUrl;
        }
    }

    @Data
    public static class Audio {
        private int sampleRate;
        private int segmentSampleRate;
        private int channels;
        private int segmentChannels;
    }

    @Data
    public static class Ffmpeg {
        private String path;
        private String probePath;
        private String encoder; // 可选：nvenc / qsv / amf / videotoolbox / software
    }

    @Data
    public static class Download {
        private String outputFilename;
        private long timeoutMs;
    }

    @Data
    public static class Device {
        private String demucs;
        private String whisper;
    }

    /**
     * 启动时调用：从 DB 读取设置，覆盖 AppProperties 中同名值。
     * DB 中存储的 key 格式如 "asr.provider"、"translate.openai.apiKey" 等。
     */
    public void mergeFromDb(SettingsRepository settingsRepository) {
        Map<String, String> db = settingsRepository.getAll();

        // 全局 / 下载 / 设备 / 编码器
        setIfPresent(db, "ytdlp.proxy", ytdlp::setProxy);
        setIfPresent(db, "download.outputFilename", download::setOutputFilename);
        setLongIfPresent(db, "download.timeoutMs", download::setTimeoutMs);
        setIfPresent(db, "device.demucs", deviceConfig::setDemucs);
        setIfPresent(db, "device.whisper", deviceConfig::setWhisper);
        setIfPresent(db, "ffmpeg.encoder", ffmpeg::setEncoder);

        // Provider 选择
        setIfPresent(db, "asr.provider", asr::setProvider);
        setIfPresent(db, "tts.provider", tts::setProvider);
        setIfPresent(db, "translate.provider", translate::setProvider);
        setIfPresent(db, "separate.provider", separate::setProvider);

        // ASR 配置
        setIfPresent(db, "asr.whisper-api.baseUrl", asr.getWhisperApi()::setBaseUrl);
        setIfPresent(db, "asr.whisper-api.url", asr.getWhisperApi()::setUrl);
        setIfPresent(db, "asr.whisper-api.apiKey", asr.getWhisperApi()::setApiKey);
        setIfPresent(db, "asr.whisper-api.model", asr.getWhisperApi()::setModel);
        setIfPresent(db, "asr.whisper-cpp.model", asr.getWhisperCpp()::setModel);

        // TTS 配置
        setIfPresent(db, "tts.edge-tts.path", tts.getEdgeTts()::setPath);
        setIfPresent(db, "tts.edge-tts.voice", tts.getEdgeTts()::setVoice);
        setIfPresent(db, "tts.openai-tts.url", tts.getOpenaiTts()::setUrl);
        setIfPresent(db, "tts.openai-tts.apiKey", tts.getOpenaiTts()::setApiKey);
        setIfPresent(db, "tts.openai-tts.model", tts.getOpenaiTts()::setModel);
        setIfPresent(db, "tts.openai-tts.voice", tts.getOpenaiTts()::setVoice);
        setIfPresent(db, "tts.voxcpm.serviceUrl", tts.getVoxcpm()::setServiceUrl);

        // 翻译配置
        setIfPresent(db, "translate.ollama.baseUrl", translate.getOllama()::setBaseUrl);
        setIfPresent(db, "translate.ollama.model", translate.getOllama()::setModel);
        setIntIfPresent(db, "translate.ollama.concurrency", translate.getOllama()::setConcurrency);
        setIfPresent(db, "translate.openai.chatUrl", translate.getOpenai()::setChatUrl);
        setIfPresent(db, "translate.openai.apiKey", translate.getOpenai()::setApiKey);
        setIfPresent(db, "translate.openai.model", translate.getOpenai()::setModel);
        setIntIfPresent(db, "translate.openai.concurrency", translate.getOpenai()::setConcurrency);

        // 分离配置
        setIfPresent(db, "separate.demucs.model", separate.getDemucs()::setModel);
        setIfPresent(db, "separate.audio-separator-api.serviceUrl", separate.getAudioSeparatorApi()::setServiceUrl);
    }

    private void setIfPresent(Map<String, String> map, String key, Consumer<String> setter) {
        String val = map.get(key);
        if (val != null && !val.isBlank()) {
            setter.accept(val);
        }
    }

    private void setIntIfPresent(Map<String, String> map, String key, Consumer<Integer> setter) {
        String val = map.get(key);
        if (val != null && !val.isBlank()) {
            try { setter.accept(Integer.parseInt(val)); } catch (NumberFormatException ignored) {}
        }
    }

    private void setLongIfPresent(Map<String, String> map, String key, Consumer<Long> setter) {
        String val = map.get(key);
        if (val != null && !val.isBlank()) {
            try { setter.accept(Long.parseLong(val)); } catch (NumberFormatException ignored) {}
        }
    }
}
