package site.dengwei.onnxruntime.whisper;

import org.jtransforms.fft.DoubleFFT_1D;

import java.util.Arrays;

/**
 * Whisper 标准 80 通道 log-mel 频谱预处理。
 * <p>算法与 OpenAI Whisper 的 {@code log_mel_spectrogram()} 一致：
 * <ol>
 *   <li>STFT（Hann 窗，fft=400，hop=160，center=true）</li>
 *   <li>幅度平方</li>
 *   <li>80 通道 Mel 滤波器组</li>
 *   <li>{@code log10(clip(min=1e-10))}</li>
 *   <li>动态范围裁剪到 80dB</li>
 * </ol>
 */
public final class MelSpectrogram {

    public static final int SAMPLE_RATE = 16000;
    public static final int N_FFT       = 400;
    public static final int HOP_LENGTH  = 160;
    public static final int N_MELS      = 80;
    public static final double MIN_DB   = 1e-10;

    private static final double[][] MEL_FILTERBANK = buildMelFilterbank();

    private MelSpectrogram() {}

    // ──────────────────────────── 公开 API ────────────────────────────

    /**
     * 将 float 音频转换为 log-mel 频谱矩阵 [melBins][frames]。
     *
     * @param audio 原始音频样本（任意采样率，方法内部 resample 到 16kHz）
     * @param sampleRate 输入音频采样率
     * @return shape=[N_MELS][frames] 的 log-mel 频谱
     */
    public static float[][] compute(float[] audio, int sampleRate) {
        if (sampleRate != SAMPLE_RATE) {
            audio = resample(audio, sampleRate, SAMPLE_RATE);
        }
        return compute(audio);
    }

    /** 直接对 16kHz 音频计算 log-mel。 */
    public static float[][] compute(float[] audio) {
        float[][] mag2 = stftMagnitudeSquared(audio);    // [201][frames]
        float[][] mel  = applyMelFilterbank(mag2);       // [80][frames]
        return logAndClip(mel);                          // [80][frames]
    }

    /** 标准化：减去均值除以标准差（按帧切 30s 窗口后逐窗口标准化）。 */
    public static void normalizeInPlace(float[][] mel, int maxFrames) {
        int frames = Math.min(mel[0].length, maxFrames);
        for (int off = 0; off < frames; off += maxFrames) {
            int end = Math.min(off + maxFrames, frames);
            int len = end - off;
            double sum = 0, sumSq = 0;
            for (int f = 0; f < N_MELS; f++) {
                for (int t = off; t < end; t++) {
                    double v = mel[f][t];
                    sum  += v;
                    sumSq += v * v;
                }
            }
            double mean = sum / (N_MELS * len);
            double var  = sumSq / (N_MELS * len) - mean * mean;
            double std  = Math.sqrt(Math.max(var, 0)) + 1e-10;
            for (int f = 0; f < N_MELS; f++) {
                for (int t = off; t < end; t++) {
                    mel[f][t] = (float) ((mel[f][t] - mean) / std);
                }
            }
        }
    }

    // ──────────────────────────── STFT ────────────────────────────

    private static float[][] stftMagnitudeSquared(float[] audio) {
        // center=True：前后 pad n_fft//2 零样本（与 torch.stft center=True 一致）
        int pad = N_FFT / 2;
        float[] padded = new float[audio.length + 2 * pad];
        System.arraycopy(audio, 0, padded, pad, audio.length);

        int freqBins = N_FFT / 2 + 1;
        int numFrames = padded.length / HOP_LENGTH + 1;
        if (numFrames <= 0) {
            throw new IllegalArgumentException("音频太短: " + audio.length + " < " + N_FFT);
        }

        float[][] mag2 = new float[freqBins][numFrames];
        double[] window = hannWindow(N_FFT);
        DoubleFFT_1D fft = new DoubleFFT_1D(N_FFT);
        double[] frame = new double[2 * N_FFT];

        for (int t = 0; t < numFrames; t++) {
            Arrays.fill(frame, 0.0);
            int offset = t * HOP_LENGTH;
            for (int i = 0; i < N_FFT && (offset + i) < padded.length; i++) {
                frame[2 * i] = padded[offset + i] * window[i];
            }
            fft.complexForward(frame);
            for (int f = 0; f < freqBins; f++) {
                double real = frame[2 * f];
                double imag = frame[2 * f + 1];
                mag2[f][t] = (float) (real * real + imag * imag);
            }
        }
        return mag2;
    }

    // ──────────────────────────── Mel 滤波器组 ────────────────────────────

    private static double[][] buildMelFilterbank() {
        int freqBins = N_FFT / 2 + 1;
        double[][] fb = new double[N_MELS][freqBins];

        double melMin = hzToMel(0);
        double melMax = hzToMel(SAMPLE_RATE / 2.0);
        double melStep = (melMax - melMin) / (N_MELS + 1);

        for (int m = 0; m < N_MELS; m++) {
            double centerMel = melMin + (m + 1) * melStep;
            double leftMel = centerMel - melStep;
            double rightMel = centerMel + melStep;
            double leftHz = melToHz(leftMel);
            double rightHz = melToHz(rightMel);
            double slaneyNorm = 2.0 / Math.max(rightHz - leftHz, 1e-10);

            for (int f = 0; f < freqBins; f++) {
                double hz = f * SAMPLE_RATE / (double) N_FFT;
                double mel = hzToMel(hz);
                if (mel < leftMel || mel > rightMel) continue;
                double weight;
                if (mel <= centerMel) {
                    weight = (mel - leftMel) / melStep;   // left slope
                } else {
                    weight = (rightMel - mel) / melStep;   // right slope
                }
                fb[m][f] = weight * slaneyNorm;
            }
        }
        return fb;
    }

    private static double hzToMel(double hz) {
        return 1127.0 * Math.log(1.0 + hz / 700.0);
    }

    private static double melToHz(double mel) {
        return 700.0 * (Math.exp(mel / 1127.0) - 1.0);
    }

    private static float[][] applyMelFilterbank(float[][] mag2) {
        int freqBins = N_FFT / 2 + 1;
        int frames = mag2[0].length;
        float[][] mel = new float[N_MELS][frames];
        for (int m = 0; m < N_MELS; m++) {
            for (int t = 0; t < frames; t++) {
                double sum = 0;
                for (int f = 0; f < freqBins; f++) {
                    sum += MEL_FILTERBANK[m][f] * mag2[f][t];
                }
                mel[m][t] = (float) sum;
            }
        }
        return mel;
    }

    // ──────────────────────────── Log + 裁剪 ────────────────────────────

    private static float[][] logAndClip(float[][] mel) {
        int frames = mel[0].length;
        float[][] out = new float[N_MELS][frames];
        double maxVal = Double.NEGATIVE_INFINITY;
        for (int m = 0; m < N_MELS; m++) {
            for (int t = 0; t < frames; t++) {
                double v = Math.max(mel[m][t], MIN_DB);
                double logV = Math.log10(v);
                out[m][t] = (float) logV;
                if (logV > maxVal) maxVal = logV;
            }
        }
        double floor = maxVal - 8.0;
        for (int m = 0; m < N_MELS; m++) {
            for (int t = 0; t < frames; t++) {
                double v = out[m][t];
                if (v < floor) v = floor;
                // Whisper 标准归一化：(log_mel + 4) / 4，值域约 [0, 1.5]
                // 不用 z-score —— 模型训练时期望的就是这个分布
                out[m][t] = (float) ((v + 4.0) / 4.0);
            }
        }
        return out;
    }

    // ──────────────────────────── 重采样 ────────────────────────────

    private static float[] resample(float[] in, int fromRate, int toRate) {
        int outLen = (int) ((long) in.length * toRate / fromRate);
        float[] out = new float[outLen];
        double ratio = (double) fromRate / toRate;
        for (int i = 0; i < outLen; i++) {
            double pos = i * ratio;
            int idx = (int) pos;
            double frac = pos - idx;
            if (idx + 1 < in.length) {
                out[i] = (float) (in[idx] * (1 - frac) + in[idx + 1] * frac);
            } else {
                out[i] = in[Math.min(idx, in.length - 1)];
            }
        }
        return out;
    }

    // ──────────────────────────── 窗函数 ────────────────────────────

    private static double[] hannWindow(int size) {
        double[] w = new double[size];
        for (int i = 0; i < size; i++) {
            w[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / size));
        }
        return w;
    }
}
