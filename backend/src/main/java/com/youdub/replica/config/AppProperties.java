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
    private String corsAllowOrigins;
    private long uploadMaxBytes;
    private long subtitleMaxBytes;

    private Openai openai = new Openai();
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
    public static class Openai {
        /** 完整 Chat Completions URL，如 https://api.openai.com/v1/chat/completions */
        private String url;
        /** 基础 URL（用于 TTS、模型列表等），留空时自动从 url 推导 */
        private String baseUrl;
        private String apiKey;
        private String model;

        public String getBaseUrl() {
            if (baseUrl != null && !baseUrl.isBlank()) return baseUrl;
            if (url != null && url.endsWith("/chat/completions")) {
                return url.substring(0, url.length() - "/chat/completions".length());
            }
            return "";
        }
    }

    @Data
    public static class Ytdlp {
        private String proxy;
    }

    @Data
    public static class Asr {
        private String provider;
        private String model;
    }

    @Data
    public static class Tts {
        private String provider;
        private String model;
        private String voice;
        private String openaiModel;
        private String openaiVoice;
        private String edgeTtsPath;
        private String voxcpmServiceUrl;
    }

    @Data
    public static class Translate {
        private String provider;
    }

    @Data
    public static class Separate {
        private String provider;
        private String model;
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
     * DB 中存储的 key 格式如 "openai.baseUrl"、"translate.concurrency"。
     */
    public void mergeFromDb(SettingsRepository settingsRepository) {
        Map<String, String> db = settingsRepository.getAll();
        setIfPresent(db, "asr.provider", asr::setProvider);
        setIfPresent(db, "asr.model", asr::setModel);
        setIfPresent(db, "translate.provider", translate::setProvider);
        setIfPresent(db, "separate.provider", separate::setProvider);
        setIfPresent(db, "separate.model", separate::setModel);
        setIfPresent(db, "download.outputFilename", download::setOutputFilename);
        setLongIfPresent(db, "download.timeoutMs", download::setTimeoutMs);
        setIfPresent(db, "device.demucs", deviceConfig::setDemucs);
        setIfPresent(db, "device.whisper", deviceConfig::setWhisper);
        setIfPresent(db, "ffmpeg.encoder", ffmpeg::setEncoder);
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
