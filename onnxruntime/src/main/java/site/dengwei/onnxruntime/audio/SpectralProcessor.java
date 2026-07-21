package site.dengwei.onnxruntime.audio;

import org.jtransforms.fft.DoubleFFT_1D;

import java.util.Arrays;

/**
 * 频谱处理器：STFT / iFFT 变换。
 * <p>用于 MDX-NET 模型的频谱域处理流程。</p>
 */
public final class SpectralProcessor {

    private final int fftSize;
    private final int hopSize;
    private final double[] window;

    /**
     * @param fftSize  FFT 大小
     * @param hopSize  帧移
     * @param periodicWindow true=periodic Hann（librosa 默认），false=symmetric Hann
     */
    public SpectralProcessor(int fftSize, int hopSize, boolean periodicWindow) {
        if (fftSize < 2) {
            throw new IllegalArgumentException("fftSize 必须 >= 2: " + fftSize);
        }
        this.fftSize = fftSize;
        this.hopSize = hopSize;
        this.window = hannWindow(fftSize, periodicWindow);
    }

    /** 向后兼容：symmetric Hann */
    public SpectralProcessor(int fftSize, int hopSize) {
        this(fftSize, hopSize, false);
    }

    // ──────────────────────────── STFT ────────────────────────────

    /**
     * 频谱数据：频率轴的第 0..freqBins-1 个 bin（直流到奈奎斯特频率）。
     */
    public record ComplexSpectrogram(float[][] real, float[][] imag, int fftSize, int hopSize) {
        public int freqBins() { return real[0].length; }
        public int frames()   { return real.length; }
    }

    /**
     * 对单声道 float 样本执行 STFT，返回复频谱。
     *
     * @param samples 归一化浮点样本 [-1.0, 1.0]
     * @return 复频谱，shape = [frames][freqBins]，freqBins = fftSize/2 + 1
     */
    public ComplexSpectrogram stft(float[] samples) {
        int freqBins = fftSize / 2 + 1;
        int numFrames = (samples.length - fftSize) / hopSize + 1;
        if (numFrames <= 0) {
            throw new IllegalArgumentException("音频太短，不足以完成一次 FFT: " + samples.length + " < " + fftSize);
        }

        float[][] real = new float[numFrames][freqBins];
        float[][] imag = new float[numFrames][freqBins];
        DoubleFFT_1D fft = new DoubleFFT_1D(fftSize);

        double[] frame = new double[2 * fftSize];
        for (int t = 0; t < numFrames; t++) {
            Arrays.fill(frame, 0.0);
            int offset = t * hopSize;
            for (int i = 0; i < fftSize && (offset + i) < samples.length; i++) {
                frame[2 * i] = samples[offset + i] * window[i];
                // frame[2*i+1] = 0 (imag 初始就是 0)
            }
            fft.complexForward(frame);
            for (int f = 0; f < freqBins; f++) {
                real[t][f] = (float) frame[2 * f];
                imag[t][f] = (float) frame[2 * f + 1];
            }
        }
        return new ComplexSpectrogram(real, imag, fftSize, hopSize);
    }

    // ──────────────────────────── iFFT ────────────────────────────

    /**
     * 从复频谱重建时域信号（overlap-add）。
     *
     * @param spec         复频谱
     * @param originalLength 原始音频长度（帧数），用于精确截断
     * @return 重建的浮点样本
     */
    public float[] istft(ComplexSpectrogram spec, int originalLength) {
        int numFrames = spec.frames();
        int freqBins = spec.freqBins();
        int expectedFreqBins = fftSize / 2 + 1;
        if (freqBins != expectedFreqBins) {
            throw new IllegalArgumentException("频率轴不匹配: " + freqBins + " != " + expectedFreqBins);
        }

        int outLen = numFrames * hopSize + fftSize;
        double[] output = new double[outLen];
        double[] norm = new double[outLen];
        DoubleFFT_1D fft = new DoubleFFT_1D(fftSize);
        double[] frame = new double[2 * fftSize];

        for (int t = 0; t < numFrames; t++) {
            Arrays.fill(frame, 0.0);
            // 填充正频率部分
            for (int f = 0; f < freqBins; f++) {
                frame[2 * f] = spec.real()[t][f];
                frame[2 * f + 1] = spec.imag()[t][f];
            }
            // 填充负频率（共轭对称填充）
            for (int f = freqBins; f < fftSize; f++) {
                int mirror = fftSize - f;
                frame[2 * f]     = frame[2 * mirror];
                frame[2 * f + 1] = -frame[2 * mirror + 1];
            }
            fft.complexInverse(frame, true);

            int offset = t * hopSize;
            for (int i = 0; i < fftSize && (offset + i) < outLen; i++) {
                // 合成窗（再乘一次 Hann）+ 平方归一化。
                // 不加窗的方案（Method A）在 MDX-NET 模型输出的频谱上会产生 Gibbs 振铃噪声，
                // 因为模型修改频谱带来的帧边界振铃不会被抑制。加窗后帧边界贡献被衰减，输出干净。
                output[offset + i] += frame[2 * i] * window[i];
                norm[offset + i]   += window[i] * window[i];
            }
        }

        // 归一化（去除窗函数效应）
        for (int i = 0; i < outLen; i++) {
            if (norm[i] > 1e-10) {
                output[i] /= norm[i];
            }
        }

        // 截断到原始长度并转换为 float
        int len = Math.min(outLen, originalLength);
        float[] result = new float[len];
        for (int i = 0; i < len; i++) {
            result[i] = (float) Math.clamp(output[i], -1.0, 1.0);
        }
        return result;
    }

    // ──────────────────────────── 窗函数 ────────────────────────────

    private static double[] hannWindow(int size, boolean periodic) {
        double[] w = new double[size];
        double denom = periodic ? size : size - 1;
        for (int i = 0; i < size; i++) {
            w[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / denom));
        }
        return w;
    }

    public int fftSize() { return fftSize; }
    public int hopSize() { return hopSize; }
}
