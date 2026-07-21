package site.dengwei.onnxruntime.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STFT/iFFT 可逆性测试 + WavAudio 读写测试。
 */
class SpectralProcessorTest {

    private static final int SAMPLE_RATE = 44100;
    private static final float TOLERANCE = 0.02f; // iFFT 重建允许误差

    @Test
    void stftRoundTrip_preservesSineWave() {
        float[] original = generateSine(440.0f, SAMPLE_RATE, 1.0f);

        SpectralProcessor sp = new SpectralProcessor(2048, 512);
        float[] reconstructed = sp.istft(sp.stft(original), original.length);

        assertEquals(original.length, reconstructed.length, "重建长度应一致");
        // Overlap-add 在首尾 fftSize/2 样本处衰减，只比较稳定区域
        assertMiddleEquals(original, reconstructed, TOLERANCE, "STFT→iFFT 应近似还原");
    }

    @Test
    void stftRoundTrip_preservesSilence() {
        float[] silence = new float[SAMPLE_RATE];
        SpectralProcessor sp = new SpectralProcessor(2048, 512);
        float[] reconstructed = sp.istft(sp.stft(silence), silence.length);

        assertEquals(silence.length, reconstructed.length);
        for (int i = 1024; i < reconstructed.length - 1024; i++) {
            assertTrue(Math.abs(reconstructed[i]) < 0.01f, "静音重建后应接近零，index=" + i);
        }
    }

    @Test
    void stftRoundTrip_preservesMultipleFrequencies() {
        float[] s1 = generateSine(220.0f, SAMPLE_RATE, 0.5f);
        float[] s2 = generateSine(880.0f, SAMPLE_RATE, 0.5f);
        float[] mix = new float[s1.length];
        for (int i = 0; i < mix.length; i++) {
            mix[i] = (s1[i] + s2[i]) / 2;
        }

        SpectralProcessor sp = new SpectralProcessor(2048, 512);
        float[] reconstructed = sp.istft(sp.stft(mix), mix.length);

        assertMiddleEquals(mix, reconstructed, TOLERANCE, "多频混合信号应近似还原");
    }

    @Test
    void wavReadWrite_roundTrip(@TempDir Path tmpDir) throws Exception {
        float[] original = generateSine(440.0f, SAMPLE_RATE, 0.5f);
        WavAudio wav = new WavAudio(original, SAMPLE_RATE, 1);

        Path wavPath = tmpDir.resolve("test.wav");
        wav.write(wavPath);

        assertTrue(wavPath.toFile().length() > 0, "WAV 文件应非空");

        WavAudio readBack = WavAudio.read(wavPath);
        assertEquals(SAMPLE_RATE, readBack.sampleRate());
        assertEquals(1, readBack.channels());
        assertEquals(original.length, readBack.frameLength());
        assertArrayEquals(original, readBack.samples(), TOLERANCE, "WAV 读写应近似还原");
    }

    @Test
    void wavMonoToStereoConversion() {
        float[] mono = generateSine(440.0f, SAMPLE_RATE, 0.1f);
        WavAudio wav = new WavAudio(mono, SAMPLE_RATE, 1);
        WavAudio stereo = wav.toStereo();

        assertEquals(2, stereo.channels());
        assertEquals(mono.length * 2, stereo.samples().length);
        // 验证左右声道相同
        for (int i = 0; i < mono.length; i++) {
            assertEquals(stereo.samples()[i * 2], stereo.samples()[i * 2 + 1], 1e-6f);
        }
    }

    @Test
    void wavStereoToMonoConversion() {
        Random random = new Random(42);
        float[] stereo = new float[44100];
        for (int i = 0; i < stereo.length; i++) {
            stereo[i] = random.nextFloat() * 2 - 1;
        }
        WavAudio wav = new WavAudio(stereo, SAMPLE_RATE, 2);
        WavAudio mono = wav.toMono();

        assertEquals(1, mono.channels());
        assertEquals(stereo.length / 2, mono.frameLength());
    }

    // ── 辅助 ──

    /** 比较两个数组的中间稳定区域（跳过首尾各 1024 样本）。 */
    private static void assertMiddleEquals(float[] expected, float[] actual, float delta, String message) {
        int skip = 1024;
        float[] midExpected = java.util.Arrays.copyOfRange(expected, skip, expected.length - skip);
        float[] midActual   = java.util.Arrays.copyOfRange(actual,   skip, actual.length - skip);
        assertArrayEquals(midExpected, midActual, delta, message);
    }

    private static float[] generateSine(float freq, int sampleRate, double durationSec) {
        int len = (int) (sampleRate * durationSec);
        float[] buf = new float[len];
        for (int i = 0; i < len; i++) {
            buf[i] = (float) Math.sin(2 * Math.PI * freq * i / sampleRate) * 0.5f;
        }
        return buf;
    }

    private static final class Random {
        private final long seed;
        private long state;

        Random(long seed) { this.seed = seed; this.state = seed; }

        float nextFloat() {
            state ^= (state << 21);
            state ^= (state >>> 35);
            state ^= (state << 4);
            return (state & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
        }
    }
}
