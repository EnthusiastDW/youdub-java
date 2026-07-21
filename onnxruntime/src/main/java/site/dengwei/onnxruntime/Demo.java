package site.dengwei.onnxruntime;

import site.dengwei.onnxruntime.separator.AudioSeparator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Demo {

    private static Path modelPath;

    public static void main(String[] args) {
        var header = "MDX-NET 音频分离 Demo（对称 vs 周期 Hann 窗）";
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.printf ("║  %-54s║%n", header);
        System.out.println("╚══════════════════════════════════════════════════════╝");

        modelPath = Paths.get("backend/data/separator-models/UVR-MDX-NET-Inst_HQ_3.onnx");
        if (!Files.exists(modelPath)) {
            modelPath = Paths.get("models/UVR-MDX-NET-Inst_HQ_3.onnx");
        }
        if (!Files.exists(modelPath)) {
            System.err.println("[错误] 找不到模型文件，请配置正确的模型路径");
            System.err.println("       模型路径: " + modelPath.toAbsolutePath());
            System.exit(1);
        }

        Path inputWav;
        if (args.length > 0) {
            inputWav = Paths.get(args[0]);
            if (!Files.exists(inputWav)) {
                System.err.println("[错误] 输入文件不存在: " + inputWav);
                System.exit(1);
            }
        } else {
            inputWav = generateTestWav();
        }
        System.out.println("[输入] " + inputWav.toAbsolutePath());

        record Config(String label, boolean periodicWindow) {}
        var configs = new Config[] {
                new Config("A-sym", false),   // symmetric Hann（当前默认）
                new Config("B-peri", true),   // periodic Hann（librosa 默认，完美 COLA）
        };

        // 注意：之前测试的 "单窗" 方案（synthesisWindow=false）已移除，
        // 因为 MDX-NET 修改后的频谱在 iFFT 时会产生 Gibbs 振铃噪声，
        // 不加合成窗无法抑制，导致 "电流声" 噪音。

        Path outputRoot = Paths.get("demo-output");
        System.out.println("──────────────────────────────────────────────────────");
        for (var cfg : configs) {
            Path outDir = outputRoot.resolve(cfg.label());
            try {
                long t0 = System.currentTimeMillis();
                int threads = site.dengwei.onnxruntime.model.MdxNetModel.defaultNumThreads();
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
        System.out.println("说明: 之前测试的「单窗」方案（synthesisWindow=false）");
        System.out.println("      因 MDX-NET 频谱修改导致 Gibbs 振铃噪声，已移除。");
    }

    private static String fmtFile(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static Path generateTestWav() {
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

        try {
            Path tmpDir = Files.createTempDirectory("demo-");
            Path wavPath = tmpDir.resolve("test_mix.wav");
            new site.dengwei.onnxruntime.audio.WavAudio(samples, sampleRate, 1).write(wavPath);
            return wavPath;
        } catch (Exception e) {
            throw new RuntimeException("生成测试音频失败", e);
        }
    }
}
