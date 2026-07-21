package site.dengwei.onnxruntime.separator;

import ai.onnxruntime.OrtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.dengwei.onnxruntime.audio.SpectralProcessor;
import site.dengwei.onnxruntime.audio.WavAudio;
import site.dengwei.onnxruntime.model.MdxNetModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class AudioSeparator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AudioSeparator.class);

    private static final int FFT_SIZE = 6144;
    private static final int HOP_SIZE = 1024;

    /** 单块时长（秒），超过此长度则分块处理 */
    private static final int CHUNK_DURATION_SEC = 600;
    /** 块间交叉淡入淡出时长（秒） */
    private static final int CROSSFADE_SEC = 15;

    private final MdxNetModel model;
    private final SpectralProcessor spectral;

    public AudioSeparator(Path modelPath, boolean gpuEnabled) {
        this(modelPath, gpuEnabled, MdxNetModel.defaultNumThreads());
    }

    public AudioSeparator(Path modelPath, boolean gpuEnabled, int numThreads) {
        this(modelPath, gpuEnabled, numThreads, false);
    }

    public AudioSeparator(Path modelPath, boolean gpuEnabled, int numThreads,
                          boolean periodicWindow) {
        if (!Files.exists(modelPath)) {
            throw new IllegalArgumentException("模型文件不存在: " + modelPath);
        }
        this.model = new MdxNetModel(modelPath, gpuEnabled, numThreads);
        this.spectral = new SpectralProcessor(FFT_SIZE, HOP_SIZE, periodicWindow);
        log.info("AudioSeparator 初始化: model={}, fft={}, hop={}, periodicWindow={}",
                modelPath, FFT_SIZE, HOP_SIZE, periodicWindow);
    }

    public void separate(Path inputWav, Path outputDir) throws IOException, OrtException {
        long t0 = System.currentTimeMillis();

        Files.createDirectories(outputDir);
        Path vocalsOut = outputDir.resolve("audio_vocals.wav");
        Path bgmOut = outputDir.resolve("audio_bgm.wav");

        if (Files.exists(vocalsOut) && Files.exists(bgmOut)) {
            log.info("分离结果已存在，跳过: {}", outputDir);
            return;
        }

        WavAudio input = WavAudio.read(inputWav);
        int sampleRate = input.sampleRate();
        int channels = input.channels();
        int frameLen = input.frameLength();

        int chunkSamples = sampleRate * CHUNK_DURATION_SEC;
        int crossfadeSamples = sampleRate * CROSSFADE_SEC;

        float[] fullVocals;
        float[] fullBgm;

        if (frameLen <= chunkSamples) {
            // 单次处理
            var result = processSegment(input.samples(), channels, frameLen);
            fullVocals = result.vocals();
            fullBgm = result.bgm();
        } else {
            // 分块 + crossfade
            fullVocals = new float[frameLen];
            fullBgm = new float[frameLen];
            int chunks = (int) Math.ceil((double) frameLen / chunkSamples);

            log.info("长音频分块处理: totalFrames={}, sampleRate={}, chunks={}, crossfade={}s",
                    frameLen, sampleRate, chunks, CROSSFADE_SEC);

            for (int i = 0; i < chunks; i++) {
                int start = i * chunkSamples;
                int end = Math.min(start + chunkSamples + crossfadeSamples, frameLen);
                int len = end - start;
                long chunkT0 = System.currentTimeMillis();

                float[] chunkSamplesArr = Arrays.copyOfRange(input.samples(), start * channels, end * channels);
                var result = processSegment(chunkSamplesArr, channels, len);

                // 写入目标位置（处理交叉区域）
                int writeLen = len;
                int dstOffset = start;
                int srcOffset = 0;

                if (i > 0) {
                    // 与前一块交叉区域：前一块的尾部 crossfadeSamples 与当前块头部重叠
                    int overlapStart = start - crossfadeSamples;
                    if (overlapStart >= 0) {
                        int overlapLen = Math.min(crossfadeSamples, len);
                        crossfadeAppend(fullVocals, result.vocals(), overlapStart, srcOffset, overlapLen, true);
                        crossfadeAppend(fullBgm, result.bgm(), overlapStart, srcOffset, overlapLen, true);
                        dstOffset = start + overlapLen;
                        srcOffset = overlapLen;
                        writeLen = len - overlapLen;
                    }
                }

                if (writeLen > 0 && dstOffset + writeLen <= frameLen) {
                    System.arraycopy(result.vocals(), srcOffset, fullVocals, dstOffset, writeLen);
                    System.arraycopy(result.bgm(), srcOffset, fullBgm, dstOffset, writeLen);
                }

                long chunkElapsed = System.currentTimeMillis() - chunkT0;
                log.info("分块 {} 处理完成: frames=[{}..{}), duration={}ms",
                        i + 1, start, end, chunkElapsed);
            }
        }

        new WavAudio(fullVocals, sampleRate, 1).toStereo().write(vocalsOut);
        new WavAudio(fullBgm, sampleRate, 1).toStereo().write(bgmOut);

        long elapsed = System.currentTimeMillis() - t0;
        log.info("音频分离完成: total={}ms, vocals={}, bgm={}", elapsed, vocalsOut, bgmOut);
    }

    /**
     * 处理一段音频样本：STFT → ONNX → iFFT
     */
    private SegmentResult processSegment(float[] samples, int channels, int frameLen)
            throws OrtException {
        float[] leftSamples = new float[frameLen];
        float[] rightSamples = new float[frameLen];

        if (channels >= 2) {
            for (int i = 0; i < frameLen; i++) {
                leftSamples[i] = samples[i * channels];
                rightSamples[i] = samples[i * channels + 1];
            }
        } else {
            System.arraycopy(samples, 0, leftSamples, 0, frameLen);
            System.arraycopy(samples, 0, rightSamples, 0, frameLen);
        }

        int padSize = FFT_SIZE / 2;
        leftSamples = reflectPad(leftSamples, padSize);
        rightSamples = reflectPad(rightSamples, padSize);

        SpectralProcessor.ComplexSpectrogram specL = spectral.stft(leftSamples);
        SpectralProcessor.ComplexSpectrogram specR = spectral.stft(rightSamples);

        MdxNetModel.SeparationResult result = model.separate(
                specL.real(), specL.imag(), specR.real(), specR.imag());

        int fullFreqBins = result.fullFreqBins();
        int modelFreqBins = model.freqBins();

        float[] vocals = istftWithPad(result.vocalsReal(), result.vocalsImag(),
                fullFreqBins, modelFreqBins, leftSamples.length);
        float[] bgm = istftWithPad(result.instReal(), result.instImag(),
                fullFreqBins, modelFreqBins, leftSamples.length);

        vocals = trimPadding(vocals, padSize, frameLen);
        bgm = trimPadding(bgm, padSize, frameLen);

        return new SegmentResult(vocals, bgm);
    }

    private float[] istftWithPad(float[][] modelReal, float[][] modelImag,
                                  int fullFreqBins, int modelFreqBins, int frameLen) {
        int frames = modelReal.length;
        float[][] paddedReal = new float[frames][fullFreqBins];
        float[][] paddedImag = new float[frames][fullFreqBins];
        for (int t = 0; t < frames; t++) {
            System.arraycopy(modelReal[t], 0, paddedReal[t], 0, modelFreqBins);
            System.arraycopy(modelImag[t], 0, paddedImag[t], 0, modelFreqBins);
        }
        var spec = new SpectralProcessor.ComplexSpectrogram(
                paddedReal, paddedImag, FFT_SIZE, HOP_SIZE);
        return spectral.istft(spec, frameLen);
    }

    /** 反射填充两端 */
    private static float[] reflectPad(float[] signal, int padSize) {
        float[] padded = new float[signal.length + 2 * padSize];
        System.arraycopy(signal, 0, padded, padSize, signal.length);
        for (int i = 0; i < padSize; i++) {
            padded[i] = signal[padSize - 1 - i];
            padded[padded.length - 1 - i] = signal[signal.length - 1 - i];
        }
        return padded;
    }

    /** 剪掉反射填充，恢复原始长度 */
    private static float[] trimPadding(float[] signal, int padSize, int originalLen) {
        float[] trimmed = new float[originalLen];
        int available = signal.length - padSize;
        int copyLen = Math.min(originalLen, available);
        System.arraycopy(signal, padSize, trimmed, 0, copyLen);
        return trimmed;
    }

    /** 交叉区域：线性淡入淡出后累加 */
    private static void crossfadeAppend(float[] dest, float[] src,
                                         int destOffset, int srcOffset, int len, boolean fadeIn) {
        for (int i = 0; i < len; i++) {
            double ratio = (double) i / len;
            double fade = fadeIn ? ratio : (1.0 - ratio);
            dest[destOffset + i] += src[srcOffset + i] * (float) fade;
        }
    }

    public void warmUp() throws OrtException {
        int freqBins = model.freqBins();
        int segFrames = model.segmentFrames();
        float[][] dummy = new float[segFrames][freqBins];
        model.separate(dummy, dummy, dummy, dummy);
        log.info("模型预热完成");
    }

    @Override
    public void close() {
        model.close();
    }

    private record SegmentResult(float[] vocals, float[] bgm) {}
}
