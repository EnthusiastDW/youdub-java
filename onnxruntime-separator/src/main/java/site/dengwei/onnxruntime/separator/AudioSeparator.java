package site.dengwei.onnxruntime.separator;

import ai.onnxruntime.OrtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.dengwei.onnxruntime.audio.SpectralProcessor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AudioSeparator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AudioSeparator.class);

    /**
     * 加载或下载 MDX-NET 模型后创建 {@code AudioSeparator}。
     * <p>
     * 若 {@code modelPath} 不存在，自动从 HuggingFace {@code debugzxcv/uvr} 下载。
     *
     * @param modelPath  .onnx 模型文件路径
     * @param gpuEnabled 是否启用 CUDA
     */
    public static AudioSeparator loadOrDownload(Path modelPath, boolean gpuEnabled) {
        return loadOrDownload(modelPath, gpuEnabled, MdxNetModel.defaultNumThreads());
    }

    /**
     * 加载或下载 MDX-NET 模型后创建 {@code AudioSeparator}。
     * <p>
     * 若 {@code modelPath} 不存在，自动从 HuggingFace {@code debugzxcv/uvr} 下载。
     *
     * @param modelPath  .onnx 模型文件路径
     * @param gpuEnabled 是否启用 CUDA
     * @param numThreads ONNX Runtime 推理线程数
     */
    public static AudioSeparator loadOrDownload(Path modelPath, boolean gpuEnabled, int numThreads) {
        try {
            SeparatorModels.ensureMdxNetFile(modelPath);
        } catch (IOException e) {
            throw new RuntimeException("自动下载 MDX-NET 模型失败：" + modelPath, e);
        }
        return new AudioSeparator(modelPath, gpuEnabled, numThreads);
    }

    private static final int FFT_SIZE = 6144;
    private static final int HOP_SIZE = 1024;

    /**
     * 单块时长（秒），超过此长度则分块处理。
     * 分块时长直接决定单块峰值内存（STFT/推理平面/istft 均随块长线性缩放）：
     * 600s 块长峰值约 2.4GB，容器内存受限时会被 OOM-kill；300s 峰值约 1.2GB。
     * 180s 块长进一步降低单块峰值，避免 44.1kHz 长音频在 -Xmx4g 下堆 OOM。
     */
    private static final int CHUNK_DURATION_SEC = 180;
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

    /**
     * 分离人声与背景音乐。
     * <p>
     * 流式分块处理：逐块读取输入 WAV（自动转 16-bit PCM）→ STFT/ONNX/iFFT → 延迟一块做
     * crossfade 累加后直接写盘。全程只保留一个分块窗口与上一块结果在内存，避免长音频整段
     * 加载（旧实现会把整段样本 + 全长度输出数组同时驻留内存，导致 OOM）。
     *
     * @param inputWav  输入 WAV（任意常见 PCM 格式，自动转换为 16-bit）
     * @param outputDir 输出目录，生成 {@code audio_vocals.wav} 与 {@code audio_bgm.wav}
     */
    public void separate(Path inputWav, Path outputDir) throws IOException, OrtException {
        long t0 = System.currentTimeMillis();

        Files.createDirectories(outputDir);
        Path vocalsOut = outputDir.resolve("audio_vocals.wav");
        Path bgmOut = outputDir.resolve("audio_bgm.wav");

        if (Files.exists(vocalsOut) && Files.exists(bgmOut)) {
            log.info("分离结果已存在，跳过: {}", outputDir);
            return;
        }

        int sampleRate;
        int channels;
        long totalFrames;
        try (AudioInputStream probe = openConverted(inputWav)) {
            AudioFormat fmt = probe.getFormat();
            sampleRate = (int) fmt.getSampleRate();
            channels = fmt.getChannels();
            totalFrames = probe.getFrameLength();
        }
        if (totalFrames <= 0) {
            throw new IOException("无法确定音频长度: " + inputWav);
        }

        long chunkSamples = (long) sampleRate * CHUNK_DURATION_SEC;
        long crossfadeSamples = (long) sampleRate * CROSSFADE_SEC;

        // 先写临时文件，处理成功后原子改名为最终文件名；
        // 避免失败残留的半截文件被 OnnxSeparator 的"已存在即跳过"误判为分离成功
        Path tmpVocals = outputDir.resolve("audio_vocals.wav.tmp");
        Path tmpBgm = outputDir.resolve("audio_bgm.wav.tmp");
        try (OutputStream outVocals = Files.newOutputStream(tmpVocals);
             OutputStream outBgm = Files.newOutputStream(tmpBgm)) {
            writeWavHeader(outVocals, sampleRate, totalFrames);
            writeWavHeader(outBgm, sampleRate, totalFrames);

            if (totalFrames <= chunkSamples) {
                // 单次处理
                float[] frameBuf = new float[(int) totalFrames * channels];
                try (AudioInputStream ais = openConverted(inputWav)) {
                    readFrames(ais, frameBuf, (int) totalFrames, channels);
                }
                var result = processSegment(frameBuf, channels, (int) totalFrames);
                writeFrames(outVocals, result.vocals(), 0, (int) totalFrames);
                writeFrames(outBgm, result.bgm(), 0, (int) totalFrames);
            } else {
                // 分块 + crossfade：延迟一块写盘，重叠区累加
                int chunks = (int) Math.ceil((double) totalFrames / chunkSamples);
                log.info("长音频分块处理: totalFrames={}, sampleRate={}, chunks={}, crossfade={}s",
                        totalFrames, sampleRate, chunks, CROSSFADE_SEC);

                float[] frameBuf = new float[(int) (chunkSamples + crossfadeSamples) * channels];
                float[] prevVocals = null;
                float[] prevBgm = null;
                int prevSkip = 0; // 上一块输出数组中无需写盘的前缀长度（等于其自身重叠长度）
                int prevLen = 0;
                boolean first = true;

                try (AudioInputStream ais = openConverted(inputWav)) {
                    for (int i = 0; i < chunks; i++) {
                        long start = i * chunkSamples;
                        int len = (int) Math.min(chunkSamples + crossfadeSamples, totalFrames - start);
                        long chunkT0 = System.currentTimeMillis();

                        readFrames(ais, frameBuf, len, channels);
                        var result = processSegment(frameBuf, channels, len);

                        if (!first) {
                            // 当前块头部淡入累加进上一块尾部（与整段处理时的 crossfadeAppend 语义一致）
                            int overlapLen = (int) Math.min(crossfadeSamples, len);
                            int dst = (int) (chunkSamples - crossfadeSamples);
                            crossfadeAppend(prevVocals, result.vocals(), dst, 0, overlapLen, true);
                            crossfadeAppend(prevBgm, result.bgm(), dst, 0, overlapLen, true);
                            // 上一块除自身重叠前缀外的区域已确定，写盘
                            writeFrames(outVocals, prevVocals, prevSkip, prevLen - prevSkip);
                            writeFrames(outBgm, prevBgm, prevSkip, prevLen - prevSkip);
                        }

                        prevVocals = result.vocals();
                        prevBgm = result.bgm();
                        prevSkip = first ? 0 : (int) Math.min(crossfadeSamples, len);
                        prevLen = len;
                        first = false;

                        long chunkElapsed = System.currentTimeMillis() - chunkT0;
                        log.info("分块 {}/{} 处理完成: frames=[{}..{}), duration={}ms",
                                i + 1, chunks, start, start + len, chunkElapsed);
                    }
                }
                // 最后一块
                writeFrames(outVocals, prevVocals, prevSkip, prevLen - prevSkip);
                writeFrames(outBgm, prevBgm, prevSkip, prevLen - prevSkip);
            }
        } catch (IOException | OrtException | RuntimeException e) {
            // 清理半截临时文件，保证重试不会被"已存在"短路
            Files.deleteIfExists(tmpVocals);
            Files.deleteIfExists(tmpBgm);
            throw e;
        }
        Files.move(tmpVocals, vocalsOut, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmpBgm, bgmOut, StandardCopyOption.REPLACE_EXISTING);

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

    /** 打开输入 WAV 并转换为 16-bit PCM AudioInputStream。 */
    private static AudioInputStream openConverted(Path wav) throws IOException {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(wav.toFile());
            AudioFormat src = ais.getFormat();
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(),
                    16,
                    src.getChannels(),
                    src.getChannels() * 2,
                    src.getSampleRate(),
                    false);
            return AudioSystem.getAudioInputStream(target, ais);
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("不支持的音频格式: " + wav, e);
        }
    }

    /** 从流中读取 frames 帧（interleaved 声道）到 out。 */
    private static void readFrames(AudioInputStream ais, float[] out, int frames, int channels)
            throws IOException {
        int bytes = frames * channels * 2;
        byte[] raw = new byte[bytes];
        int off = 0;
        while (off < bytes) {
            int n = ais.read(raw, off, bytes - off);
            if (n < 0) break;
            off += n;
        }
        int samples = off / 2;
        ByteBuffer bb = ByteBuffer.wrap(raw, 0, off).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            out[i] = bb.getShort() / 32768.0f;
        }
    }

    /** 写入 16-bit 立体声 WAV 文件头（44 字节）。 */
    private static void writeWavHeader(OutputStream out, int sampleRate, long totalFrames)
            throws IOException {
        long dataLen = totalFrames * 4L; // 16-bit 立体声
        if (dataLen > Integer.MAX_VALUE - 36L) {
            throw new IOException("音频过长，超出 WAV 格式上限: " + totalFrames + " 帧");
        }
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt((int) (36 + dataLen));
        bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(16);
        bb.putShort((short) 1);      // PCM
        bb.putShort((short) 2);      // 立体声
        bb.putInt(sampleRate);
        bb.putInt(sampleRate * 4);   // 字节率
        bb.putShort((short) 4);      // 块对齐
        bb.putShort((short) 16);     // 位深
        bb.put("data".getBytes(StandardCharsets.US_ASCII));
        bb.putInt((int) dataLen);
        out.write(bb.array());
    }

    /** 将单声道样本写为 16-bit 立体声 PCM（双声道拷贝），分批写避免大块临时数组。 */
    private static void writeFrames(OutputStream out, float[] mono, int offset, int count)
            throws IOException {
        byte[] buf = new byte[1 << 20]; // 1MB
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        int idx = offset;
        int remaining = count;
        while (remaining > 0) {
            int batch = Math.min(remaining, buf.length / 4);
            bb.clear();
            for (int i = 0; i < batch; i++) {
                float s = mono[idx + i];
                short v = (short) Math.round(Math.clamp(s, -1.0, 1.0) * 32767.0);
                bb.putShort(v);
                bb.putShort(v);
            }
            out.write(buf, 0, batch * 4);
            idx += batch;
            remaining -= batch;
        }
    }

    public void warmUp() throws OrtException {
        int freqBins = model.freqBins();
        int segFrames = model.segmentFrames();
        float[][] dummy = new float[segFrames][freqBins];
        int maxAttempts = 3;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                model.separate(dummy, dummy, dummy, dummy);
                log.info("模型预热完成（尝试 {} 次）", i + 1);
                return;
            } catch (OrtException e) {
                if (i == maxAttempts - 1) {
                    throw e;
                }
                long delay = (long) Math.pow(2, i) * 1000L;
                log.warn("模型预热失败（第 {} 次），{}ms 后重试: {}", i + 1, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("预热中断", ie);
                }
            }
        }
    }

    @Override
    public void close() {
        model.close();
    }

    private record SegmentResult(float[] vocals, float[] bgm) {}
}
