package com.youdub.replica.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
        private FfmpegSimple ffmpegSimple = new FfmpegSimple();

        @Data
        public static class Demucs {
            private String model;
        }

        @Data
        public static class AudioSeparatorApi {
            private String serviceUrl;
        }

        @Data
        public static class FfmpegSimple {
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
}
