package site.dengwei.onnxruntime.whisper;

import site.dengwei.onnxruntime.audio.WavAudio;

import site.dengwei.onnxruntime.util.Models;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Whisper ONNX 语音识别 Demo。
 * <p>
 * 模型路径可通过环境变量 {@code WHISPER_ONNX_MODEL} 选择（默认 {@code whisper-tiny.en}），
 * 首次启动自动下载。也可直接传入音频文件路径：
 * <pre>
 *   mvn exec:java -pl onnxruntime \
 *       -Dexec.mainClass="site.dengwei.onnxruntime.whisper.WhisperDemo" \
 *       -Dexec.args="D:\audio\speech.wav"
 * </pre>
 */
public class WhisperDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       Whisper ONNX 语音识别 Demo               ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        // 1. 定位/下载模型
        String modelName = resolveModelName();
        Path modelDir = resolveModelDir(modelName);
        if (modelDir == null) {
            modelDir = Paths.get("data", "whisper-models", modelName);
        }
        System.out.println("[模型] " + modelName + " → " + modelDir.toAbsolutePath());

        // 2. 定位输入音频
        Path inputWav;
        if (args.length > 0) {
            inputWav = Paths.get(args[0]);
            if (!Files.exists(inputWav)) {
                System.err.println("[错误] 文件不存在: " + inputWav);
                System.exit(1);
                return;
            }
        } else {
            inputWav = generateTestWav();
        }
        System.out.println("[输入] " + inputWav.toAbsolutePath());

        // 3. 读取音频
        WavAudio wav = WavAudio.read(inputWav);
        double durationSec = (double) wav.frameLength() / wav.sampleRate();
        System.out.println("[音频] " + wav.sampleRate() + "Hz "
                + wav.channels() + "ch "
                + String.format("%.1fs", durationSec));

        // 4. 混音到单声道
        float[] audio = wav.channels() > 1 ? wav.toMono().samples() : wav.samples();

        // 5. 转录（loadOrDownload 自动处理缺失文件下载）
        System.out.println("[识别中...]");
        long t0 = System.currentTimeMillis();

        try (WhisperModel whisper = WhisperModel.loadOrDownload(modelDir, "en")) {
            String text = whisper.transcribe(audio, wav.sampleRate());
            long elapsed = System.currentTimeMillis() - t0;
            System.out.println("[完成] " + elapsed + "ms");
            System.out.println("──────────────────────────────────────────────");
            System.out.println(text);
            System.out.println("──────────────────────────────────────────────");
        }
    }

    /** 搜索模型目录：data/whisper-models/ → models/ → onnxruntime/models/ */
    private static Path resolveModelDir(String modelName) {
        Path dir = Models.resolveModelDir(modelName, "whisper-models", "encoder_model.onnx");
        return Files.exists(dir.resolve("encoder_model.onnx")) ? dir : null;
    }

    private static String resolveModelName() {
        String env = System.getenv("WHISPER_ONNX_MODEL");
        String name = (env != null && !env.isBlank()) ? env.trim() : "whisper-tiny.en";
        if (!name.startsWith("whisper-")) {
            name = "whisper-" + name;
        }
        return name;
    }

    /** 生成一段简短的测试语音。 */
    private static Path generateTestWav() throws Exception {
        int sampleRate = 16000;
        int durationSec = 4;
        int len = sampleRate * durationSec;
        float[] samples = new float[len];

        for (int i = 0; i < len; i++) {
            double t = (double) i / sampleRate;
            double pitch = 180 + 40 * Math.sin(2 * Math.PI * 0.8 * t);
            double voice = 0.3 * Math.sin(2 * Math.PI * pitch * t);
            voice += 0.12 * Math.sin(2 * Math.PI * pitch * 2 * t);
            voice += 0.06 * Math.sin(2 * Math.PI * pitch * 3 * t);
            double envelope = Math.min(1.0, t * 4) * Math.max(0, 1.0 - (t - 2) / 2);
            samples[i] = (float) (voice * envelope * 0.5);
        }

        Path tmpDir = Files.createTempDirectory("whisper-demo-");
        Path wavPath = tmpDir.resolve("test_speech.wav");
        new WavAudio(samples, sampleRate, 1).write(wavPath);
        return wavPath;
    }
}
