package site.dengwei.onnxruntime.separator;

import site.dengwei.onnxruntime.audio.WavAudio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MDX-NET 音频分离 Demo。
 * <p>
 * 比较对称 Hann 窗和 periodic Hann 窗对分离效果的影响。
 * <pre>
 * mvn exec:java -pl onnxruntime \
 *     -Dexec.mainClass="site.dengwei.onnxruntime.separator.SeparatorDemo" \
 *     -Dexec.args="D:\audio\mix.wav"
 * </pre>
 */
public class SeparatorDemo {

    public static void main(String[] args) throws Exception {
        var header = "MDX-NET 音频分离 Demo（对称 vs 周期 Hann 窗）";
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.printf ("║  %-54s║%n", header);
        System.out.println("╚══════════════════════════════════════════════════════╝");

        Path modelPath = resolveModel();
        System.out.println("[模型] " + modelPath.toAbsolutePath());

        Path inputWav;
        if (args.length > 0) {
            inputWav = Paths.get(args[0]);
            if (!Files.exists(inputWav)) {
                System.err.println("[错误] 输入文件不存在: " + inputWav);
                System.exit(1);
                return;
            }
        } else {
            inputWav = generateTestWav();
        }
        System.out.println("[输入] " + inputWav.toAbsolutePath());

        record Config(String label, boolean periodicWindow) {}
        var configs = new Config[] {
                new Config("A-sym", false),
                new Config("B-peri", true),
        };

        Path outputRoot = Paths.get("demo-output");
        System.out.println("──────────────────────────────────────────────────────");
        for (var cfg : configs) {
            Path outDir = outputRoot.resolve(cfg.label());
            try {
                long t0 = System.currentTimeMillis();
                int threads = MdxNetModel.defaultNumThreads();
                    try (var separator = new AudioSeparator(modelPath, false, threads,
                            cfg.periodicWindow())) {
                    separator.separate(inputWav, outDir);
                }
                long elapsed = System.currentTimeMillis() - t0;

                Path vocals = outDir.resolve("audio_vocals.wav");
                Path bgm = outDir.resolve("audio_bgm.wav");
                long vSize = Files.size(vocals);
                long bSize = Files.size(bgm);

                System.out.printf("[%s] %6dms  %s  %s%n",
                        cfg.label(), elapsed,
                        fmtFile(vSize), fmtFile(bSize));
            } catch (Exception e) {
                System.err.printf("[%s] FAIL: %s%n", cfg.label(), e.getMessage());
            }
        }
        System.out.println("──────────────────────────────────────────────────────");
        System.out.println("输出目录: " + outputRoot.toAbsolutePath());
        System.out.println("配置对照:");
        System.out.println("  A: symmetric Hann  ← 当前默认");
        System.out.println("  B: periodic Hann   ← librosa 默认（完美 COLA）");
    }

    private static Path resolveModel() {
        Path path = Paths.get("data", "separator-models", "UVR-MDX-NET-Inst_HQ_3.onnx");
        if (Files.exists(path)) return path.toAbsolutePath();
        path = Paths.get("models", "UVR-MDX-NET-Inst_HQ_3.onnx");
        if (Files.exists(path)) return path.toAbsolutePath();
        path = Paths.get("onnxruntime", "models", "UVR-MDX-NET-Inst_HQ_3.onnx");
        if (Files.exists(path)) return path.toAbsolutePath();
        return Paths.get("data", "separator-models", "UVR-MDX-NET-Inst_HQ_3.onnx").toAbsolutePath();
    }

    private static String fmtFile(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static Path generateTestWav() throws Exception {
        int sampleRate = 44100;
        int durationSec = 6;
        int len = sampleRate * durationSec;
        float[] samples = new float[len];

        for (int i = 0; i < len; i++) {
            double t = (double) i / sampleRate;
            double freqMod = 300 + 150 * Math.sin(2 * Math.PI * 1.5 * t);
            double speech = 0.4 * Math.sin(2 * Math.PI * freqMod * t);
            speech += 0.15 * Math.sin(2 * Math.PI * freqMod * 2 * t);
            speech += 0.08 * Math.sin(2 * Math.PI * freqMod * 3 * t);
            samples[i] = (float) speech;
        }

        for (int i = 0; i < len; i++) {
            double t = (double) i / sampleRate;
            double bg = 0.25 * Math.sin(2 * Math.PI * 110 * t);
            bg += 0.15 * Math.sin(2 * Math.PI * 220 * t);
            bg += 0.10 * Math.sin(2 * Math.PI * 440 * t);
            double beat = Math.sin(2 * Math.PI * t / 0.5);
            if (beat > 0.95) bg += 0.3;
            samples[i] += (float) bg;
        }
        float max = 0;
        for (float s : samples) max = Math.max(max, Math.abs(s));
        if (max > 1) for (int i = 0; i < len; i++) samples[i] /= max;

        Path tmpDir = Files.createTempDirectory("demo-");
        Path wavPath = tmpDir.resolve("test_mix.wav");
        new WavAudio(samples, sampleRate, 1).write(wavPath);
        return wavPath;
    }
}
