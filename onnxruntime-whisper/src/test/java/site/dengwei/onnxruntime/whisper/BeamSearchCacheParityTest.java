package site.dengwei.onnxruntime.whisper;

import org.junit.jupiter.api.Test;
import site.dengwei.onnxruntime.OnnxRuntimeEnv;
import site.dengwei.onnxruntime.audio.WavAudio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对拍测试：同一音频下，KV-cache 版 beam search（{@code runDecoderBeamSearchCached}）
 * 与全量版 beam search（{@code runDecoderBeamSearchFull}）必须产生完全一致的 token 序列。
 * <p>
 * 两个方法均为 private，通过反射调用。模型用本地 whisper-tiny.en（含 merged decoder，
 * usePastCache=true），同一 encoderState/initTokens/温度/预算喂给两条路径。
 */
class BeamSearchCacheParityTest {

    private static final java.nio.file.Path MODEL_DIR =
            Paths.get("..", "backend", "data", "whisper-models", "whisper-tiny.en").toAbsolutePath();

    @Test
    void cachedAndFullProduceIdenticalTokens() throws Exception {
        assumeTrue(OnnxRuntimeEnv.isNativeAvailable(), "ONNX Runtime 本机库不可用");
        assumeTrue(Files.exists(MODEL_DIR.resolve("decoder_model_merged.onnx")),
                "merged decoder 不存在: " + MODEL_DIR);
        java.nio.file.Path audioFile = Paths.get("C:/Users/deng_/AppData/Local/Temp/tiny5s.wav");
        assumeTrue(Files.exists(audioFile), "音频文件不存在: " + audioFile);

        WavAudio wav = WavAudio.read(audioFile);
        float[] audio = wav.channels() > 1 ? wav.toMono().samples() : wav.samples();
        int sampleRate = wav.sampleRate();

        try (WhisperModel model = new WhisperModel(MODEL_DIR, "en")) {
            // 复现 transcribe 的 mel 预处理
            float[][] mel = MelSpectrogram.compute(audio, sampleRate);
            if (mel[0].length > 3000) {
                mel = trimMel(mel, 3000);
            } else if (mel[0].length < 3000) {
                mel = padMel(mel, 3000);
            }

            // 反射：runEncoder(mel) → encoderState
            Method runEncoder = WhisperModel.class.getDeclaredMethod("runEncoder", float[][].class);
            runEncoder.setAccessible(true);
            float[] encoderState = (float[]) runEncoder.invoke(model, (Object) mel);
            assertNotNull(encoderState);

            // 反射：读取 initialTokens（含 SOT 等引导 token）
            Field initialTokensField = WhisperModel.class.getDeclaredField("initialTokens");
            initialTokensField.setAccessible(true);
            int[] initTokens = (int[]) initialTokensField.get(model);
            assertNotNull(initTokens);
            System.out.println("initTokens=" + Arrays.toString(initTokens));

            int beamSize = 5;
            float temperature = 0.0f;

            // 全量版
            long[] fullBudget = {100000};
            Method fullMethod = WhisperModel.class.getDeclaredMethod(
                    "runDecoderBeamSearchFull",
                    float[].class, int.class, float.class, int[].class, long[].class);
            fullMethod.setAccessible(true);
            long t0 = System.nanoTime();
            int[] fullTokens = (int[]) fullMethod.invoke(
                    model, encoderState, beamSize, temperature, initTokens, fullBudget);
            long fullMs = (System.nanoTime() - t0) / 1_000_000;

            // 缓存版
            long[] cachedBudget = {100000};
            Method cachedMethod = WhisperModel.class.getDeclaredMethod(
                    "runDecoderBeamSearchCached",
                    float[].class, int.class, float.class, int[].class, long[].class);
            cachedMethod.setAccessible(true);
            long t1 = System.nanoTime();
            int[] cachedTokens = (int[]) cachedMethod.invoke(
                    model, encoderState, beamSize, temperature, initTokens, cachedBudget);
            long cachedMs = (System.nanoTime() - t1) / 1_000_000;

            System.out.println("full 版 tokens=" + Arrays.toString(fullTokens)
                    + " (" + fullMs + "ms, 剩余预算=" + fullBudget[0] + ")");
            System.out.println("cached 版 tokens=" + Arrays.toString(cachedTokens)
                    + " (" + cachedMs + "ms, 剩余预算=" + cachedBudget[0] + ")");
            System.out.println("文本: full=\"" + decode(model, fullTokens) + "\" cached=\""
                    + decode(model, cachedTokens) + "\"");

            assertArrayEquals(fullTokens, cachedTokens,
                    "KV-cache 版与全量版 beam search 输出不一致");
        }
    }

    private static String decode(WhisperModel model, int[] tokens) throws Exception {
        Field tokenizerField = WhisperModel.class.getDeclaredField("tokenizer");
        tokenizerField.setAccessible(true);
        Object tokenizer = tokenizerField.get(model);
        Method decodeMethod = tokenizer.getClass().getMethod("decode", int[].class);
        return (String) decodeMethod.invoke(tokenizer, (Object) tokens);
    }

    private static float[][] trimMel(float[][] mel, int maxFrames) {
        float[][] out = new float[mel.length][];
        for (int i = 0; i < mel.length; i++) {
            out[i] = Arrays.copyOf(mel[i], maxFrames);
        }
        return out;
    }

    private static float[][] padMel(float[][] mel, int targetFrames) {
        float[][] out = new float[mel.length][];
        for (int i = 0; i < mel.length; i++) {
            out[i] = Arrays.copyOf(mel[i], targetFrames);
        }
        return out;
    }
}
