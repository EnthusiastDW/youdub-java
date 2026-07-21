package site.dengwei.onnxruntime.separator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.dengwei.onnxruntime.OnnxRuntimeEnv;
import site.dengwei.onnxruntime.audio.WavAudio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AudioSeparator 端到端测试。
 * <p>需要模型文件 {@code onnxruntime/models/UVR-MDX-NET-Inst_HQ_3.onnx}。</p>
 * <p>标记为 {@code @Tag("integration")}，默认不执行。使用 {@code mvn test -Dgroups=integration} 运行。</p>
 */
@Tag("integration")
class AudioSeparatorTest {

    private static final Path MODEL_PATH = Paths.get("models/UVR-MDX-NET-Inst_HQ_3.onnx");
    private static final int SAMPLE_RATE = 44100;

    @BeforeAll
    static void checkEnvironment() {
        assumeTrue(OnnxRuntimeEnv.isNativeAvailable(),
                "ONNX Runtime 本机库不可用，跳过集成测试。请安装 VC++ 2015-2022 Redistributable (x64)。");
        assumeTrue(Files.exists(MODEL_PATH),
                "模型文件不存在: " + MODEL_PATH.toAbsolutePath()
                        + " — 请运行 scripts/download-model.bat 下载");
    }

    @Test
    void separateSineWave_producesOutputFiles(@TempDir Path tmpDir) throws Exception {
        // 生成 6 秒混合信号（MDX-NET segment=256帧 @ hop=1024 ≈ 5.94秒）
        float[] samples = new float[SAMPLE_RATE * 6];
        for (int i = 0; i < samples.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            samples[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * t)
                    + 0.3 * Math.sin(2 * Math.PI * 880 * t));
        }

        Path inputWav = tmpDir.resolve("input.wav");
        new WavAudio(samples, SAMPLE_RATE, 1).write(inputWav);

        Path outputDir = tmpDir.resolve("output");
        try (AudioSeparator separator = new AudioSeparator(MODEL_PATH, false)) {
            separator.warmUp();
            separator.separate(inputWav, outputDir);
        }

        Path vocals = outputDir.resolve("audio_vocals.wav");
        Path bgm = outputDir.resolve("audio_bgm.wav");

        assertTrue(Files.exists(vocals), "vocals 输出文件应存在");
        assertTrue(Files.exists(bgm), "bgm 输出文件应存在");
        assertTrue(Files.size(vocals) > 0, "vocals 应非空");
        assertTrue(Files.size(bgm) > 0, "bgm 应非空");

        // 验证输出是有效 WAV
        WavAudio vocalsWav = WavAudio.read(vocals);
        WavAudio bgmWav = WavAudio.read(bgm);
        assertEquals(2, vocalsWav.channels(), "输出应为立体声");
        assertEquals(2, bgmWav.channels(), "输出应为立体声");
        assertEquals(SAMPLE_RATE, vocalsWav.sampleRate(), "采样率应保持不变");

        System.out.println("vocals: " + vocalsWav.durationSec() + "s, " + vocalsWav.frameLength() + " frames");
        System.out.println("bgm: " + bgmWav.durationSec() + "s, " + bgmWav.frameLength() + " frames");
    }

    @Test
    void separateRealisticAudio(@TempDir Path tmpDir) throws Exception {
        // 生成模拟语音 + 背景音的混合信号（简化模拟）
        float[] samples = new float[SAMPLE_RATE * 6];
        for (int i = 0; i < samples.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            // "人声": 调幅 200-400Hz 模拟语音
            double speech = 0.4 * Math.sin(2 * Math.PI * (250 + 100 * Math.sin(2 * Math.PI * 2 * t)) * t);
            // "背景音": 稳定的低频 + 高频
            double bg = 0.3 * Math.sin(2 * Math.PI * 120 * t) + 0.2 * Math.sin(2 * Math.PI * 1000 * t);
            samples[i] = (float) (speech + bg);
        }

        Path inputWav = tmpDir.resolve("mix.wav");
        new WavAudio(samples, SAMPLE_RATE, 1).write(inputWav);

        Path outputDir = tmpDir.resolve("separated");
        try (AudioSeparator separator = new AudioSeparator(MODEL_PATH, false)) {
            separator.separate(inputWav, outputDir);
        }

        Path vocals = outputDir.resolve("audio_vocals.wav");
        Path bgm = outputDir.resolve("audio_bgm.wav");

        assertTrue(Files.exists(vocals));
        assertTrue(Files.exists(bgm));
        assertTrue(Files.size(vocals) > 1000);
        assertTrue(Files.size(bgm) > 1000);

        System.out.println("Realistic test - vocals: " + Files.size(vocals) + " bytes, bgm: " + Files.size(bgm) + " bytes");
    }
}
