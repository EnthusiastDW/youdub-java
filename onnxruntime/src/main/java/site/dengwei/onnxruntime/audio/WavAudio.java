package site.dengwei.onnxruntime.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * WAV 音频数据容器。
 * <p>统一规格：16-bit PCM，读取时自动转换为此规格；写入时也按此规格输出。</p>
 */
public final class WavAudio {

    private final float[] samples;
    private final int sampleRate;
    private final int channels;

    /**
     * @param samples    归一化浮点样本 [-1.0, 1.0]，interleaved 排列
     * @param sampleRate 采样率（Hz）
     * @param channels   声道数
     */
    public WavAudio(float[] samples, int sampleRate, int channels) {
        this.samples = Objects.requireNonNull(samples);
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    // ──────────────────────────── 读取 ────────────────────────────

    /** 从 WAV 文件读取，自动转换为 16-bit PCM。 */
    public static WavAudio read(Path path) throws IOException {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat src = ais.getFormat();

            // 转 16-bit PCM
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(),
                    16,
                    src.getChannels(),
                    src.getChannels() * 2,     // frameSize
                    src.getSampleRate(),
                    false                       // little-endian (WAV 标准)
            );
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, ais)) {
                return readStream(converted);
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("不支持的音频格式: " + path, e);
        }
    }

    private static WavAudio readStream(AudioInputStream ais) throws IOException {
        AudioFormat fmt = ais.getFormat();
        byte[] raw = ais.readAllBytes();
        int sampleRate = (int) fmt.getSampleRate();
        int channels = fmt.getChannels();

        float[] samples = new float[raw.length / 2];
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = bb.getShort() / 32768.0f;
        }
        return new WavAudio(samples, sampleRate, channels);
    }

    // ──────────────────────────── 写入 ────────────────────────────

    /** 写入为 16-bit PCM WAV 文件。 */
    public void write(Path path) throws IOException {
        AudioFormat fmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false
        );
        byte[] raw = toByteArray();
        try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(raw), fmt, raw.length / fmt.getFrameSize())) {
            AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    /** 写入到 OutputStream（WAV 格式）。 */
    public void write(OutputStream out) throws IOException {
        Path tmp = Files.createTempFile("wav-", ".wav");
        try {
            write(tmp);
            Files.copy(tmp, out);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private byte[] toByteArray() {
        ByteBuffer bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            short v = (short) Math.round(Math.clamp(s, -1.0, 1.0) * 32767.0);
            bb.putShort(v);
        }
        return bb.array();
    }

    // ──────────────────────────── 工具 ────────────────────────────

    /** 混音下混为单声道（所有声道取平均）。 */
    public WavAudio toMono() {
        if (channels == 1) return this;
        int frameLen = samples.length / channels;
        float[] mono = new float[frameLen];
        for (int i = 0; i < frameLen; i++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += samples[i * channels + c];
            }
            mono[i] = sum / channels;
        }
        return new WavAudio(mono, sampleRate, 1);
    }

    /** 扩充为立体声（单声道 → 双声道拷贝）。 */
    public WavAudio toStereo() {
        if (channels == 2) return this;
        float[] stereo = new float[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            stereo[i * 2] = samples[i];
            stereo[i * 2 + 1] = samples[i];
        }
        return new WavAudio(stereo, sampleRate, 2);
    }

    public float[] samples()  { return samples; }
    public int sampleRate()   { return sampleRate; }
    public int channels()     { return channels; }
    public int frameLength()  { return samples.length / channels; }
    public double durationSec() { return (double) frameLength() / sampleRate; }
}
