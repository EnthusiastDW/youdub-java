package com.youdub.replica.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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
    private AsrCorrectorConfig asrCorrector = new AsrCorrectorConfig();
    private Tts tts = new Tts();
    private Translate translate = new Translate();
    private Separate separate = new Separate();
    private Audio audio = new Audio();
    private Ffmpeg ffmpeg = new Ffmpeg();
    private Download download = new Download();
    private Device deviceConfig = new Device();
    private Pipeline pipeline = new Pipeline();

    @Data
    public static class Ytdlp {
        private String proxy;
    }

    @Data
    public static class Asr {
        private String provider;
        private WhisperApi whisperApi = new WhisperApi();
        private WhisperCpp whisperCpp = new WhisperCpp();
        private OnnxWhisper onnxWhisper = new OnnxWhisper();

        @Data
        public static class WhisperApi {
            private String baseUrl;
            private String url;
            private String apiKey;
            private String model;
        }

        @Data
        public static class WhisperCpp {
            private String modelDir;
            private String model;
            private String modelPath;
            private String vadModel;
            private String vadModelPath;
            private boolean vad = true;
            private int threads = 8;
            private int beamSize = 5;
            private String prompt;
            private int chunkMinutes = 10;
            private int timeoutMs = 0;
            /** 关闭跨段历史条件化（--no-context），防止重复循环与幻觉 */
            private boolean noContext = true;
            /** VAD 阈值（0~1），分离后的人声音频干净，调低以捕获轻声段 */
            private double vadThreshold = 0.3;
            /** VAD 最小静音时长（ms），防止句中被切断 */
            private int vadMinSilenceMs = 200;
            /** 熵阈值，拒绝低熵（重复）输出，抑制幻觉 */
            private double entropyThold = 2.6;
            /** 对数概率阈值，拒绝低置信度片段 */
            private double logprobThold = -1.25;
            /** 是否在喂给 whisper 前做 loudnorm + highpass 音频预处理 */
            private boolean preprocess = true;
        }

        @Data
        public static class OnnxWhisper {
        }
    }

    @Data
    public static class AsrCorrectorConfig {
        private String provider = "openai-asr-corrector";
        private OpenaiAsrCorrector openaiAsrCorrector = new OpenaiAsrCorrector();

        @Data
        public static class OpenaiAsrCorrector {
            private boolean enabled = false;
            private String chatUrl;
            private String apiKey;
            private String model;
        }
    }

    @Data
    public static class Tts {
        private String provider;
        private EdgeTts edgeTts = new EdgeTts();
        private OpenaiTts openaiTts = new OpenaiTts();
        private Voxcpm voxcpm = new Voxcpm();
        private VoxcpmCpp voxcpmCpp = new VoxcpmCpp();

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
            /** gpt-4o-mini-tts 的风格控制指令（instructions），保证跨句音色/语速一致 */
            private String instructions;
        }

        @Data
        public static class Voxcpm {
            private String serviceUrl;
        }

        @Data
        public static class VoxcpmCpp {
            private String path;
            private String modelDir;
            private String baseLmModel;
            private String acousticModel;
            private double cfgValue;
            private int timesteps;
            private int seed;
            private long timeoutMs;
            private int concurrency;
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
        private FfmpegSimple ffmpegSimple = new FfmpegSimple();
        private OnnxSeparatorConfig onnx = new OnnxSeparatorConfig();

        @Data
        public static class Demucs {
            private String model;
        }

        @Data
        public static class FfmpegSimple {
        }

        @Data
        public static class OnnxSeparatorConfig {
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

    @Data
    public static class Pipeline {
        private Map<String, Integer> concurrency = new HashMap<>();
    }
}
