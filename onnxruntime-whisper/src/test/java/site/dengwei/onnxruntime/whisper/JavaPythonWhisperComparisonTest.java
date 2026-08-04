package site.dengwei.onnxruntime.whisper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import site.dengwei.onnxruntime.OnnxRuntimeEnv;
import site.dengwei.onnxruntime.audio.WavAudio;
import site.dengwei.onnxruntime.whisper.WhisperModel.Segment;
import site.dengwei.onnxruntime.whisper.WhisperModel.TranscriptionResult;
import site.dengwei.onnxruntime.whisper.WhisperModel.Word;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Java ONNX Whisper (medium) vs Python faster-whisper (medium.en) 对比。
 *
 * 以 Python 端输出为基准。本测试用 whisper-medium 处理与 Python 相同的音频，
 * 输出 OpenAI verbose_json 同构 JSON 到 asr-java.json，供对比脚本对齐。
 *
 * 标记为 {@code @Tag("integration")}，需用 {@code mvn test -Dgroups=integration} 运行。
 */
@Tag("integration")
class JavaPythonWhisperComparisonTest {

    private static Path projectRoot() {
        Path cwd = Paths.get(".").toAbsolutePath().normalize();
        if (cwd.endsWith("onnxruntime-whisper") || cwd.toString().contains("onnxruntime-whisper")) {
            return cwd.getParent();
        }
        if (cwd.endsWith("onnxruntime") || cwd.toString().contains("onnxruntime")) {
            return cwd.getParent();
        }
        return cwd;
    }

    private static final Path PROJ = projectRoot();
    private static final Path MODEL_DIR = PROJ.resolve("backend/data/whisper-models/whisper-medium.en");
    private static final String TASK_DIR = "backend/workfolder/local"
            + "/Rusts For Loop Is Smarter Than You Think  Advanced Rust Part 12__3d9b99ae8ecc43ea";
    private static final Path AUDIO_FILE = PROJ.resolve(TASK_DIR + "/media/audio_vocals.wav");
    private static final Path OUTPUT_FILE = PROJ.resolve(TASK_DIR + "/metadata/asr-java.json");

    @Test
    void runJavaWhisperMedium() throws Exception {
        assumeTrue(OnnxRuntimeEnv.isNativeAvailable(), "ONNX Runtime 本机库不可用");
        assumeTrue(Files.exists(AUDIO_FILE), "音频文件不存在: " + AUDIO_FILE);

        System.out.println("\n=== 加载模型(必要时自动下载): " + MODEL_DIR + " ===");
        long tLoad0 = System.currentTimeMillis();
        WhisperModel model;
        try {
            model = WhisperModel.loadOrDownload(MODEL_DIR, "en");
        } catch (Throwable t) {
            throw new RuntimeException("模型加载失败: " + t.getMessage(), t);
        }
        long loadMs = System.currentTimeMillis() - tLoad0;
        System.out.println("模型加载耗时: " + loadMs + "ms");

        System.out.println("\n=== 读取音频: " + AUDIO_FILE + " ===");
        WavAudio wav = WavAudio.read(AUDIO_FILE);
        float[] audio = wav.channels() > 1 ? wav.toMono().samples() : wav.samples();
        int sampleRate = wav.sampleRate();
        System.out.println("音频: " + (double) audio.length / sampleRate + "s, "
                + sampleRate + "Hz, " + wav.channels() + "ch");

        System.out.println("\n=== 开始转录（transcribeChunked, medium）===");
        long t1 = System.currentTimeMillis();
        TranscriptionResult result;
        try {
            result = model.transcribeChunked(audio, sampleRate);
        } finally {
            model.close();
        }
        long transcribeMs = System.currentTimeMillis() - t1;
        int wordCount = result.segments().stream().mapToInt(s -> s.words().size()).sum();
        System.out.println("转录完成: " + transcribeMs + "ms, "
                + "segments=" + result.segments().size()
                + ", words=" + wordCount);
        System.out.println("全文:\n" + result.fullText());

        // 构建 OpenAI verbose_json 同构输出（segments 时间用秒，对齐 Python）
        JSONObject out = new JSONObject();
        out.put("engine", "java-onnx-whisper-medium.en");
        out.put("text", result.fullText());
        out.put("duration", round3((double) audio.length / sampleRate));
        JSONObject timing = new JSONObject();
        timing.put("load_ms", loadMs);
        timing.put("transcribe_ms", transcribeMs);
        out.put("timing_ms", timing);

        JSONArray segments = new JSONArray();
        for (int i = 0; i < result.segments().size(); i++) {
            Segment seg = result.segments().get(i);
            JSONObject so = new JSONObject();
            so.put("id", i);
            so.put("start", round3(seg.startMs() / 1000.0));
            so.put("end", round3(seg.endMs() / 1000.0));
            so.put("text", seg.text());
            JSONArray words = new JSONArray();
            for (Word w : seg.words()) {
                JSONObject wo = new JSONObject();
                wo.put("word", w.text());
                wo.put("start", round3(w.startMs() / 1000.0));
                wo.put("end", round3(w.endMs() / 1000.0));
                words.put(wo);
            }
            so.put("words", words);
            segments.put(so);
        }
        out.put("segments", segments);

        Files.createDirectories(OUTPUT_FILE.getParent());
        Files.writeString(OUTPUT_FILE, out.toString(2));
        System.out.println("\n已保存: " + OUTPUT_FILE);

        assertTrue(result.segments().size() > 0, "无转录结果");
        assertTrue(wordCount > 0, "无单词");
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
