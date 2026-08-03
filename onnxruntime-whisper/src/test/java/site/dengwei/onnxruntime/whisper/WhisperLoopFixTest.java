package site.dengwei.onnxruntime.whisper;

import org.junit.jupiter.api.Test;
import site.dengwei.onnxruntime.audio.WavAudio;
import site.dengwei.onnxruntime.whisper.WhisperModel.TranscriptionResult;

import java.nio.file.Paths;

/**
 * 临时回归：验证贪心重复循环修复 + beam 预算。
 * 用 2 分钟真实语音跑 transcribeChunked，观察：
 * 1) 贪心不再退化 443 token（应提前终止）
 * 2) 总耗时从 >5min 降至可接受范围
 */
class WhisperLoopFixTest {

    private static final java.nio.file.Path MODEL_DIR =
            Paths.get("..", "backend", "data", "whisper-models", "whisper-tiny").toAbsolutePath();

    @Test
    void transcribeChunkedReal() throws Exception {
        WavAudio wav = WavAudio.read(Paths.get("C:/Users/deng_/AppData/Local/Temp/real_speech_2min.wav"));
        float[] audio = wav.channels() > 1 ? wav.toMono().samples() : wav.samples();
        int sampleRate = wav.sampleRate();
        System.out.println("音频: " + (double) audio.length / sampleRate + "s, " + sampleRate + "Hz");

        try (WhisperModel model = new WhisperModel(MODEL_DIR, "auto")) {
            long t0 = System.currentTimeMillis();
            TranscriptionResult result = model.transcribeChunked(audio, sampleRate);
            long elapsed = System.currentTimeMillis() - t0;
            System.out.println("=== transcribeChunked 完成, 耗时 " + elapsed + "ms, segments="
                    + result.segments().size() + " ===");
            System.out.println("全文: " + result.fullText());
        }
    }

    @Test
    void tinyEnShort() throws Exception {
        java.nio.file.Path enDir =
                Paths.get("..", "data", "whisper-models", "whisper-tiny.en").toAbsolutePath();
        int sampleRate = 16000;
        int len = sampleRate * 4;
        float[] samples = new float[len];
        for (int i = 0; i < len; i++) {
            double t = (double) i / sampleRate;
            samples[i] = (float) (0.3 * Math.sin(2 * Math.PI * 180 * t)
                    * Math.min(1.0, t * 4) * Math.max(0, 1.0 - (t - 2) / 2));
        }
        try (WhisperModel model = new WhisperModel(enDir, "en")) {
            long t0 = System.currentTimeMillis();
            String text = model.transcribe(samples, sampleRate);
            System.out.println("=== whisper-tiny.en 短转录完成, 耗时 " + (System.currentTimeMillis() - t0)
                    + "ms, text=\"" + text + "\" ===");
        }
    }
}
