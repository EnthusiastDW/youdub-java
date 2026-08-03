package site.dengwei.onnxruntime.whisper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 语音活动检测（VAD）和静音点分片工具。
 * <p>
 * 基于 RMS 能量 + 自适应阈值的 VAD 实现，对齐 Python faster-whisper 的静音检测和分片策略。
 * 所有方法均为无状态的静态方法，可直接调用。
 */
public final class WhisperVad {

    /** 最小静音时长（秒），≥此值的静音才作为分片点。对齐 Python SILENCE_MIN_DURATION。 */
    private static final float SILENCE_MIN_SEC = 2.0f;
    /** 分片间重叠（秒）。对齐 Python SILENCE_OVERLAP。 */
    private static final float CHUNK_OVERLAP_SEC = 2.0f;
    /** 最小分片时长（秒），短于此值的与邻居合并。 */
    private static final float MIN_CHUNK_SEC = 5.0f;

    private WhisperVad() {}

    // ────────────────────── VAD 滤波 ──────────────────────

    /**
     * 能量基 VAD：移除静音段，返回仅含语音段的音频（拼接后保持采样率）。
     */
    public static float[] vadFilter(float[] audio, int sampleRate) {
        int frameLen = sampleRate / 100;          // 10ms
        int frameShift = frameLen / 3;             // ~3.3ms shift（高分辨率边界）
        if (audio.length < frameLen) return audio;

        int numFrames = (audio.length - frameLen) / frameShift + 1;
        float[] rms = computeRms(audio, frameLen, frameShift, numFrames);
        float threshold = computeAdaptiveThreshold(rms);

        boolean[] isSpeech = new boolean[numFrames];
        for (int i = 0; i < numFrames; i++) isSpeech[i] = rms[i] > threshold;

        int maxSilenceFrames = 300 * (sampleRate / 1000) / frameShift;
        boolean[] merged = mergeSpeechSegments(isSpeech, maxSilenceFrames);

        int minSpeechFrames = 100 * (sampleRate / 1000) / frameShift;
        merged = removeShortSegments(merged, minSpeechFrames);

        List<float[]> segments = extractAudioSegments(audio, merged, frameLen, frameShift);
        if (segments.isEmpty()) return audio;

        int totalLen = segments.stream().mapToInt(s -> s.length).sum();
        float[] result = new float[totalLen];
        int pos = 0;
        for (float[] seg : segments) {
            System.arraycopy(seg, 0, result, pos, seg.length);
            pos += seg.length;
        }
        return result;
    }

    /**
     * 合并静音间隙 ≤ maxGap 帧的语音段。
     */
    public static boolean[] mergeSpeechSegments(boolean[] isSpeech, int maxGap) {
        boolean[] merged = isSpeech.clone();
        int n = merged.length;
        for (int i = 0; i < n; ) {
            if (!merged[i]) { i++; continue; }
            int end = i;
            while (end < n && merged[end]) end++;
            int silence = 0;
            while (i < n && !merged[i] && silence < maxGap) {
                silence++;
                i++;
            }
            if (i < n && merged[i]) {
                for (int j = end; j < i; j++) merged[j] = true;
            }
        }
        return merged;
    }

    /**
     * 移除长度不足 minLen 帧的短语音段（噪声误判过滤）。
     */
    public static boolean[] removeShortSegments(boolean[] isSpeech, int minLen) {
        boolean[] result = isSpeech.clone();
        int n = result.length;
        for (int i = 0; i < n; ) {
            if (!result[i]) { i++; continue; }
            int start = i;
            while (i < n && result[i]) i++;
            if (i - start < minLen) {
                for (int j = start; j < i; j++) result[j] = false;
            }
        }
        return result;
    }

    // ──────────── 静音点检测 & 分片 ────────────

    /**
     * 检测音频中 ≥2s 的静音区域，返回 [startSample, endSample] 列表。
     * <p>
     * 使用与 vadFilter 相同的 RMS 能量检测 + 自适应阈值。
     * 帧长为 10ms（无重叠），确保与 Python faster-whisper 的 chunk_length_s 对齐。
     */
    public static List<int[]> detectSilenceRegions(float[] audio, int sampleRate) {
        List<int[]> regions = new ArrayList<>();
        int frameLen = sampleRate / 100;
        int frameShift = frameLen;
        int numFrames = (audio.length - frameLen) / frameShift + 1;
        if (numFrames < 2) return regions;

        float[] rms = computeRms(audio, frameLen, frameShift, numFrames);
        float threshold = computeAdaptiveThreshold(rms);

        boolean[] isSilence = new boolean[numFrames];
        for (int i = 0; i < numFrames; i++) isSilence[i] = rms[i] <= threshold;

        int minSilenceFrames = (int) (SILENCE_MIN_SEC * sampleRate / frameShift);
        for (int i = 0; i < numFrames; ) {
            if (!isSilence[i]) { i++; continue; }
            int start = i;
            while (i < numFrames && isSilence[i]) i++;
            if (i - start >= minSilenceFrames) {
                int startSample = start * frameShift;
                int endSample = Math.min(i * frameShift + frameLen, audio.length);
                regions.add(new int[]{startSample, endSample});
            }
        }
        return regions;
    }

    /**
     * 按静音区域构建分片。对齐 Python _build_chunk_boundaries + _split_audio_chunks。
     * <p>
     * 规则：
     * 1. 取每个静音区域中点为 split point
     * 2. 跳过距开头/末尾不足 60s 的 split point（避免产生过小分片）
     * 3. 分片边界 = split point ± CHUNK_OVERLAP_SEC
     * 4. 不足 MIN_CHUNK_SEC 的分片与前后合并
     *
     * @return true=使用静音分片, false=回退到固定窗口
     */
    public static boolean buildSilenceChunks(
            float[] audio, int sampleRate,
            List<int[]> silenceRegions,
            List<float[]> chunksOut, List<Long> offsetsOut) {

        int totalSamples = audio.length;
        double totalSec = (double) totalSamples / sampleRate;

        List<Double> splitPoints = new ArrayList<>();
        for (int[] region : silenceRegions) {
            double midSec = (double) (region[0] + region[1]) / 2.0 / sampleRate;
            if (midSec > 60.0 && (totalSec - midSec) > 60.0) {
                splitPoints.add(midSec);
            }
        }

        if (splitPoints.isEmpty()) return false;

        List<double[]> boundaries = new ArrayList<>();
        double prevSplit = 0;
        for (double sp : splitPoints) {
            double startSec = prevSplit > 0 ? Math.max(0, prevSplit - CHUNK_OVERLAP_SEC) : 0;
            double endSec = Math.min(totalSec, sp + CHUNK_OVERLAP_SEC);
            if (endSec > startSec) boundaries.add(new double[]{startSec, endSec});
            prevSplit = sp;
        }
        double lastStart = Math.max(0, splitPoints.get(splitPoints.size() - 1) - CHUNK_OVERLAP_SEC);
        boundaries.add(new double[]{lastStart, totalSec});

        List<double[]> merged = new ArrayList<>();
        for (double[] b : boundaries) {
            if (merged.isEmpty()) {
                merged.add(b);
            } else {
                double[] last = merged.get(merged.size() - 1);
                double curLen = b[1] - b[0];
                if (curLen < MIN_CHUNK_SEC || (b[1] - last[0]) <= MIN_CHUNK_SEC * 1.5) {
                    last[1] = b[1];
                } else {
                    merged.add(b);
                }
            }
        }

        for (double[] b : merged) {
            int startSample = Math.max(0, (int) (b[0] * sampleRate));
            int endSample = Math.min(totalSamples, (int) (b[1] * sampleRate));
            if (endSample <= startSample) continue;
            chunksOut.add(Arrays.copyOfRange(audio, startSample, endSample));
            offsetsOut.add((long) (b[0] * 1000));
        }

        return !chunksOut.isEmpty();
    }

    // ──────────── 内部工具 ────────────

    /** 计算每帧的 RMS 能量值。 */
    private static float[] computeRms(float[] audio, int frameLen, int frameShift, int numFrames) {
        float[] rms = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            int off = i * frameShift;
            double sum = 0;
            for (int j = 0; j < frameLen; j++) sum += audio[off + j] * audio[off + j];
            rms[i] = (float) Math.sqrt(sum / frameLen);
        }
        return rms;
    }

    /** 自适应阈值：取最低 20% 帧的均值 × 2.5。 */
    private static float computeAdaptiveThreshold(float[] rms) {
        float[] sorted = rms.clone();
        Arrays.sort(sorted);
        float noiseFloor = 0;
        int n20 = Math.max(1, sorted.length / 5);
        for (int i = 0; i < n20; i++) noiseFloor += sorted[i];
        noiseFloor /= n20;
        return Math.max(noiseFloor * 2.5f, 1e-6f);
    }

    /** 提取语音帧对应的音频段列表。 */
    private static List<float[]> extractAudioSegments(float[] audio, boolean[] speech, int frameLen, int frameShift) {
        List<float[]> segments = new ArrayList<>();
        int segStart = -1;
        for (int i = 0; i < speech.length; i++) {
            if (speech[i] && segStart < 0) {
                segStart = i * frameShift;
            } else if (!speech[i] && segStart >= 0) {
                int segEnd = Math.min(i * frameShift + frameLen, audio.length);
                segments.add(Arrays.copyOfRange(audio, segStart, segEnd));
                segStart = -1;
            }
        }
        if (segStart >= 0) {
            segments.add(Arrays.copyOfRange(audio, segStart, audio.length));
        }
        return segments;
    }
}
