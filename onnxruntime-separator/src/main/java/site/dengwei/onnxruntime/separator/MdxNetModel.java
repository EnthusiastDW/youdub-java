package site.dengwei.onnxruntime.separator;

import ai.onnxruntime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class MdxNetModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MdxNetModel.class);

    private final OrtEnvironment env;
    private final OrtSession session;
    private final int freqBins;
    private final int segmentFrames;
    private final int numTracks;

    /** 滑窗重叠率，与 audio-separator 默认值一致：0.5 重叠会使推理次数多 1.5×，是 CPU 慢的主因 */
    private static final double SLIDING_WINDOW_OVERLAP = 0.25;

    /**
     * @param modelPath  模型文件路径 (.onnx)
     * @param gpuEnabled 是否启用 CUDA
     */
    public MdxNetModel(Path modelPath, boolean gpuEnabled) {
        this(modelPath, gpuEnabled, defaultNumThreads());
    }

    /**
     * @param modelPath  模型文件路径 (.onnx)
     * @param gpuEnabled 是否启用 CUDA
     * @param numThreads ONNX Runtime 推理线程数（intra-op）
     */
    public MdxNetModel(Path modelPath, boolean gpuEnabled, int numThreads) {
        this.env = OrtEnvironment.getEnvironment();

        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        try {
            opts.setIntraOpNumThreads(numThreads);
            opts.setMemoryPatternOptimization(true);
            opts.setCPUArenaAllocator(false);
            log.info("MDX-NET CPU 推理线程数: {}", numThreads);
            if (gpuEnabled) {
                opts.addCUDA();
                log.info("MDX-NET 启用 CUDA 加速");
            }
        } catch (OrtException e) {
            log.warn("SessionOptions 配置失败，使用默认设置: {}", e.getMessage());
        }

        try {
            this.session = env.createSession(modelPath.toString(), opts);
        } catch (OrtException e) {
            throw new RuntimeException("加载 ONNX 模型失败: " + modelPath, e);
        }

        int resolvedFreqBins;
        int resolvedSegFrames;
        try {
            var inputInfo = session.getInputInfo();
            var entry = inputInfo.entrySet().iterator().next();
            long[] inputShape = ((TensorInfo) entry.getValue().getInfo()).getShape();
            if (inputShape.length != 4) {
                throw new IllegalArgumentException("MDX-NET 模型应接受 4 维输入，实际: " + inputShape.length);
            }
            resolvedFreqBins = (int) inputShape[2];
            resolvedSegFrames = (int) inputShape[3];
            log.info("MDX-NET 模型加载: input={}x{}x{}x{}", inputShape[0], inputShape[1], resolvedFreqBins, resolvedSegFrames);
        } catch (OrtException e) {
            throw new RuntimeException("获取模型输入信息失败", e);
        }

        int resolvedNumTracks;
        try {
            var outputInfo = session.getOutputInfo();
            var entry = outputInfo.entrySet().iterator().next();
            long[] outputShape = ((TensorInfo) entry.getValue().getInfo()).getShape();
            resolvedNumTracks = (int) outputShape[1] / 2;
            log.info("MDX-NET 输出轨道数: {}, shape={}", resolvedNumTracks, Arrays.toString(outputShape));
        } catch (OrtException e) {
            throw new RuntimeException("获取模型输出信息失败", e);
        }

        this.freqBins = resolvedFreqBins;
        this.segmentFrames = resolvedSegFrames;
        this.numTracks = resolvedNumTracks;
    }

    /**
     * 执行 MDX-NET 推理。
     * <p>
     * 模型输入为 4 通道复频谱: [L_real, L_imag, R_real, R_imag]。
     * 立体声时左右声道分别 STFT，单声道时左右使用相同数据。
     * 输入的频率轴会自动截断/填充以匹配模型期望的 {@code freqBins}。
     *
     * @param leftReal  左声道（或单声道）实部 [frames][freqBins]
     * @param leftImag  左声道（或单声道）虚部 [frames][freqBins]
     * @param rightReal 右声道实部 [frames][freqBins]
     * @param rightImag 右声道虚部 [frames][freqBins]
     * @return 分离结果
     */
    public SeparationResult separate(float[][] leftReal, float[][] leftImag,
                                     float[][] rightReal, float[][] rightImag) throws OrtException {
        int totalFrames = leftReal.length;
        int inputFreqBins = leftReal[0].length;
        int modelFreqBins = this.freqBins;
        int useFreqBins = Math.min(inputFreqBins, modelFreqBins);

        int segFrames = segmentFrames;
        int hopFrames = (int) (segFrames * (1 - SLIDING_WINDOW_OVERLAP));

        // 只累加第一个有效轨道（inst），人声通过减法得到
        float[][] instReal = new float[totalFrames][modelFreqBins];
        float[][] instImag = new float[totalFrames][modelFreqBins];
        int[][] accCount = new int[totalFrames][modelFreqBins];

        for (int start = 0; start < totalFrames; start += hopFrames) {
            int end = Math.min(start + segFrames, totalFrames);
            int actualFrames = end - start;

            float[] inputData = new float[4 * modelFreqBins * segFrames];
            int planeSize = modelFreqBins * segFrames;
            for (int t = 0; t < segFrames; t++) {
                int srcT = Math.min(start + t, totalFrames - 1);
                for (int f = 0; f < useFreqBins; f++) {
                    inputData[f * segFrames + t] = leftReal[srcT][f];
                    inputData[planeSize + f * segFrames + t] = leftImag[srcT][f];
                    inputData[2 * planeSize + f * segFrames + t] = rightReal[srcT][f];
                    inputData[3 * planeSize + f * segFrames + t] = rightImag[srcT][f];
                }
            }

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(inputData), new long[]{1, 4, modelFreqBins, segFrames})) {

                try (OrtSession.Result result = session.run(
                        Map.of(session.getInputInfo().keySet().iterator().next(), inputTensor))) {
                    float[][] output = extractOutput(result);

                    for (int t = 0; t < actualFrames; t++) {
                        int dstT = start + t;
                        for (int f = 0; f < modelFreqBins; f++) {
                            instReal[dstT][f] += output[0][f * segFrames + t];
                            instImag[dstT][f] += output[1][f * segFrames + t];
                            accCount[dstT][f]++;
                        }
                    }
                }
            }
        }

        // 平均重叠部分，并计算人声 = 输入 - 乐器
        float[][] vocReal = new float[totalFrames][modelFreqBins];
        float[][] vocImag = new float[totalFrames][modelFreqBins];
        for (int t = 0; t < totalFrames; t++) {
            for (int f = 0; f < modelFreqBins; f++) {
                int count = Math.max(accCount[t][f], 1);
                instReal[t][f] /= count;
                instImag[t][f] /= count;
                vocReal[t][f] = leftReal[t][f] - instReal[t][f];
                vocImag[t][f] = leftImag[t][f] - instImag[t][f];
            }
        }
        return new SeparationResult(vocReal, vocImag, instReal, instImag, inputFreqBins);
    }

    private float[][] extractOutput(OrtSession.Result result) throws OrtException {
        var entry = result.iterator().next();
        OnnxValue value = entry.getValue();
        float[] data;
        long[] shape;

        if (value instanceof OnnxTensor tensor) {
            data = tensor.getFloatBuffer().array();
            shape = ((TensorInfo) tensor.getInfo()).getShape();
        } else {
            throw new RuntimeException("意料之外的输出类型: " + value.getClass());
        }

        int outChannels = (int) shape[1];
        int outFreq = (int) shape[2];
        int outFrames = (int) shape[3];
        int frameSize = outFreq * outFrames;

        float[][] resultArr = new float[outChannels][frameSize];
        for (int c = 0; c < outChannels; c++) {
            System.arraycopy(data, c * frameSize, resultArr[c], 0, frameSize);
        }
        return resultArr;
    }

    public static int defaultNumThreads() {
        String env = System.getenv("ONNX_NUM_THREADS");
        if (env != null && !env.isBlank()) {
            try {
                int n = Integer.parseInt(env.trim());
                return Math.max(1, n);
            } catch (NumberFormatException e) {
                log.warn("ONNX_NUM_THREADS 格式无效: {}, 使用默认值", env);
            }
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
    }

    public int freqBins()        { return freqBins; }
    public int segmentFrames()   { return segmentFrames; }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            log.warn("关闭 ONNX session 失败", e);
        }
    }

    /** MDX-NET 分离结果。{@code fullFreqBins} 为原始 STFT 的频率轴长度。 */
    public record SeparationResult(
            float[][] vocalsReal, float[][] vocalsImag,
            float[][] instReal,   float[][] instImag,
            int fullFreqBins
    ) {}
}
