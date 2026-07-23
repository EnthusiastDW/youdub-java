package site.dengwei.onnxruntime.whisper;

import ai.onnxruntime.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.dengwei.onnxruntime.whisper.WhisperModels;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Whisper ONNX 语音识别模型。
 * <p>
 * 在 Java 进程内使用 ONNX Runtime 加载 Whisper encoder + decoder ONNX 模型，
 * 完成音频 → 文本的转录。
 * <p>
 * 模型目录结构要求：
 * <pre>
 * model-dir/
 *   ├── config.json          — 模型配置
 *   ├── encoder_model.onnx   — 编码器（mel → hidden states）
 *   ├── decoder_model_fp16.onnx   — 解码器（自回归文本生成，fp16）
 *   ├── vocab.json           — 词表
 *   └── merges.txt           — BPE 合并规则
 * </pre>
 */
public final class WhisperModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WhisperModel.class);

    private final OrtEnvironment env;
    private final OrtSession encoderSession;
    private OrtSession decoderSession;
    private final WhisperConfig config;
    private final WhisperTokenizer tokenizer;

    // 模型目录和语言设置（forkWorker 需要，也是从 loadOrDownload 到构造器的透传）
    private final Path modelDir;
    private final String language;

    // 输入/输出名称（从 ONNX 模型实际元数据自动发现）
    private final String encoderInputName;
    private final String encoderOutputName;
    private final String decoderInputIdsName;
    private final String decoderEncoderStateName;
    private final String decoderLogitsName;

    // 特殊 token ID（来源于 config.json，不在 vocab.json 中）
    private final int eotToken;
    // 解码器初始 token 序列：[SOT, <|lang|>, <|transcribe|>, <|notimestamps|>]
    private int[] initialTokens;
    // 解码器初始 token 序列（带时间戳版，不含 no_timestamps）：[SOT, <|lang|>, <|transcribe|>]
    private int[] initialTokensWithTimestamps;

    // 自动检测语言（language=null/blank/"auto" 时启用，首次转录时检测）
    private final boolean languageAuto;
    // 语言 token 映射（generation_config.json → 回退硬编码），供 detectLanguage 使用
    private final Map<String, Integer> langTokens;

    // 诊断标志：仅第一个 chunk 打印详细统计
    private volatile boolean firstDiag = true;

    // KV-cache 增量推理（仅 decoder_with_past_model.onnx 支持）
    private final boolean usePastCache;
    private final List<String> pastInputNames;    // past_key_values.0.decoder.key, ...
    private final List<String> presentOutputNames; // present.0.decoder.key, ...
    private final String useCacheBranchName;       // use_cache_branch（merged 模型特有，null=非 merged）

    // Whisper 多语言标准特殊 token ID（来自 generation_config，所有导出版本统一）
    private static final int TRANSCRIBE_TOKEN = 50359;
    private static final int NO_TIMESTAMPS_TOKEN = 50363;

    // 语言代码 → 语言 token ID 回退表（仅含前20个常用语言，ID 50259-50278 连续）。
    // 特殊 token 不在 vocab.json 里，无法用 tokenizer 查询。
    // 完整映射优先从 generation_config.json 的 lang_to_id 动态读取（见 loadLangTokens）。
    private static final Map<String, Integer> LANG_TOKENS_FALLBACK = Map.ofEntries(
            Map.entry("en", 50259), Map.entry("zh", 50260), Map.entry("de", 50261),
            Map.entry("es", 50262), Map.entry("ru", 50263), Map.entry("ko", 50264),
            Map.entry("fr", 50265), Map.entry("ja", 50266), Map.entry("pt", 50267),
            Map.entry("tr", 50268), Map.entry("pl", 50269), Map.entry("ca", 50270),
            Map.entry("nl", 50271), Map.entry("ar", 50272), Map.entry("sv", 50273),
            Map.entry("it", 50274), Map.entry("id", 50275), Map.entry("hi", 50276),
            Map.entry("fi", 50277), Map.entry("vi", 50278)
    );

    // 最大帧数（30 秒音频的 mel 帧数）
    private static final int MAX_MEL_FRAMES = 3000;

    /**
     * 加载或下载 Whisper ONNX 模型。
     * <p>
     * 若 {@code modelDir} 下缺少必需文件（encoder/decoder ONNX、config 等），
     * 自动从 HuggingFace {@code onnx-community/{modelName}} 下载。
     *
     * @param modelDir 模型文件目录
     * @param language 语言代码（如 "en"、"zh"）
     */
    /**
     * 创建一个轻量工作实例，与本实例共享 WhisperConfig、WhisperTokenizer，
     * 但拥有独立的 ONNX encoder/decoder 会话，可用于多线程并行解码。
     * <p>
     * 工作实例关闭时仅释放自身的会话，不影响原始实例。
     * 调用方负责 {@link #close()} 每个 fork 的 worker。
     * worker 不支持自动语言检测（使用已确定的 initialTokens）。
     */
    public WhisperModel forkWorker() {
        if (initialTokens == null) {
            throw new IllegalStateException("forkWorker 要求 initialTokens 已确定（auto-detect 模式下需先调用一次 transcribe）");
        }
        WhisperModel worker = new WhisperModel(modelDir, language);
        // auto-detect 模式下，fork 的 worker 应使用已确定的 initialTokens，无需重新检测
        worker.initialTokens = this.initialTokens;
        worker.initialTokensWithTimestamps = this.initialTokensWithTimestamps;
        // 关闭 worker 构造器中 auto-detect 可能遗留的资源（无实际操作，仅防御）
        worker.firstDiag = false;
        return worker;
    }

    public static WhisperModel loadOrDownload(Path modelDir, String language) {
        try {
            WhisperModels.ensureFiles(modelDir);
        } catch (IOException e) {
            throw new RuntimeException("自动下载 Whisper 模型失败：" + modelDir, e);
        }
        return new WhisperModel(modelDir, language);
    }

    /**
     * @param modelDir 包含 Whisper ONNX 模型和词表的目录
     * @param language 目标语言代码（如 "en", "zh"），用于查找对应语言 token
     */
    public WhisperModel(Path modelDir, String language) {
        this.env = OrtEnvironment.getEnvironment();
        this.modelDir = modelDir;
        this.language = language;
        this.config = new WhisperConfig(modelDir);
        this.tokenizer = new WhisperTokenizer(modelDir);

        // 语言 token 映射：优先从 generation_config.json 动态读取，回退到硬编码常用语言
        this.langTokens = loadLangTokens(modelDir);

        // 自动检测语言模式：language 为 null/blank/"auto" 时启用
        // 首次转录时 detectLanguage 会从 encoder 输出推断语言，然后才构建 initialTokens
        boolean autoDetect = language == null || language.isBlank() || "auto".equalsIgnoreCase(language);
        this.languageAuto = autoDetect;

        try {
            this.encoderSession = loadSession(config.encoderModelPath());
            this.decoderSession = loadSession(config.decoderModelPath());

            this.encoderInputName = discoverInputName(encoderSession, "input_features", "mel");
            this.encoderOutputName = discoverOutputName(encoderSession, "last_hidden_state", "encoder_output");
            this.decoderInputIdsName = discoverInputName(decoderSession, "input_ids");
            this.decoderEncoderStateName = discoverInputName(decoderSession, "encoder_hidden_states", "encoder_output");
            this.decoderLogitsName = discoverOutputName(decoderSession, "logits");

            // 发现 KV-cache 接口（decoder_with_past 模型）
            java.util.Set<String> decoderInputNames = decoderSession.getInputInfo().keySet();
            java.util.Set<String> decoderOutputNames = decoderSession.getOutputInfo().keySet();
            this.pastInputNames = new ArrayList<>();
            this.presentOutputNames = new ArrayList<>();
            String foundUseCacheBranch = null;
            for (String name : decoderInputNames) {
                if (name.equals(decoderInputIdsName) || name.equals(decoderEncoderStateName)) continue;
                if (name.contains("use_cache") || name.contains("cache_branch")) {
                    foundUseCacheBranch = name;
                    continue;
                }
                pastInputNames.add(name);
            }
            this.useCacheBranchName = foundUseCacheBranch;
            for (String name : decoderOutputNames) {
                if (!name.equals(decoderLogitsName)) {
                    presentOutputNames.add(name);
                }
            }
            java.util.Collections.sort(pastInputNames);
            java.util.Collections.sort(presentOutputNames);
            this.usePastCache = !pastInputNames.isEmpty() && !presentOutputNames.isEmpty()
                    && !decoderEncoderStateName.equals(decoderInputIdsName);

            this.eotToken = config.eosTokenId();
            int sot = config.decoderStartTokenId();
            if (languageAuto) {
                // auto-detect 模式：initialTokens 在首次 detectLanguage 后构建
                this.initialTokens = null;
                this.initialTokensWithTimestamps = null;
            } else {
                int langTokenId = resolveLangToken(language, langTokens);
                this.initialTokens = new int[]{sot, langTokenId, TRANSCRIBE_TOKEN, NO_TIMESTAMPS_TOKEN};
                this.initialTokensWithTimestamps = new int[]{sot, langTokenId, TRANSCRIBE_TOKEN};
            }
            log.info("WhisperModel 初始化完成: language={} auto={} initial_tokens={} eot={} usePastCache={}",
                    language, languageAuto, initialTokens, eotToken, usePastCache);
            log.info("encoder 输入={} 输出={}", encoderSession.getInputInfo().keySet(), encoderSession.getOutputInfo().keySet());
            log.info("decoder 输入={} 输出={}", decoderSession.getInputInfo().keySet(), decoderSession.getOutputInfo().keySet());
        } catch (OrtException e) {
            throw new RuntimeException("WhisperModel 初始化失败", e);
        }
    }

    /**
     * 从 generation_config.json 加载语言 token 映射（lang_to_id）。
     * 文件不存在时回退到硬编码的常用语言映射。
     */
    private static Map<String, Integer> loadLangTokens(Path modelDir) {
        Path genConfig = modelDir.resolve("generation_config.json");
        if (Files.exists(genConfig)) {
            try {
                String content = Files.readString(genConfig);
                JSONObject json = new JSONObject(content);
                JSONObject langToId = json.optJSONObject("lang_to_id");
                if (langToId != null) {
                    Map<String, Integer> map = new HashMap<>(langToId.length());
                    for (String key : langToId.keySet()) {
                        // key 形如 "<|zh|>"，去掉 <| |> 得到 "zh"
                        String lang = key.replaceAll("^<\\|", "").replaceAll("\\|>$", "");
                        map.put(lang, langToId.getInt(key));
                    }
                    log.info("从 generation_config.json 加载语言映射：{} 种语言", map.size());
                    return map;
                }
            } catch (Exception e) {
                log.warn("读取 generation_config.json 失败，回退到硬编码映射：{}", e.getMessage());
            }
        } else {
            log.warn("generation_config.json 不存在，使用硬编码常用语言映射（{}种）。建议下载该文件以支持全部语言。",
                    LANG_TOKENS_FALLBACK.size());
        }
        return LANG_TOKENS_FALLBACK;
    }

    private int resolveLangToken(String language, Map<String, Integer> langTokens) {
        if (language == null || language.isBlank()) language = "en";
        Integer id = langTokens.get(language);
        if (id == null) {
            log.warn("未知语言代码 {}，回退到 en(50259)", language);
            return 50259;
        }
        return id;
    }

    // ────────────────────── 语言自动检测 ──────────────────────

    /**
     * 从编码器输出推断音频语言，返回检测到的语言 token ID。
     * <p>
     * 方法：向解码器只送入 {@code [SOT]} token，取第一步 logits 中
     * 概率最高的语言 token（仅考虑 {@link #langTokens} 中的 token ID）。
     */
    private int detectLanguage(float[] encoderOutput) throws OrtException {
        long[] inputIds = new long[]{config.decoderStartTokenId()};
        int srcLen = encoderOutput.length / config.dModel();
        long[] encShape = {1, srcLen, config.dModel()};

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env,
                LongBuffer.wrap(inputIds), new long[]{1, 1});
             OnnxTensor encTensor = OnnxTensor.createTensor(env,
                     FloatBuffer.wrap(encoderOutput), encShape)) {
            Map<String, OnnxTensorLike> inputs = new LinkedHashMap<>();
            inputs.put(decoderInputIdsName, idsTensor);
            inputs.put(decoderEncoderStateName, encTensor);
            try (OrtSession.Result result = decoderSession.run(inputs)) {
                OnnxTensor logitsTensor = (OnnxTensor) result.get(decoderLogitsName).orElseThrow();
                float[] logits = logitsTensor.getFloatBuffer().array();

                int bestToken = -1;
                float bestVal = Float.NEGATIVE_INFINITY;
                // 只考虑 langTokens 中的语言 token ID，防止选中非语言 token
                for (int v : langTokens.values()) {
                    if (v < 0 || v >= logits.length) continue;
                    if (config.isSuppressToken(v)) continue;
                    if (logits[v] > bestVal) {
                        bestVal = logits[v];
                        bestToken = v;
                    }
                }

                if (bestToken < 0) {
                    log.warn("语言检测失败，回退到 en(50259)");
                    return 50259;
                }
                log.info("语言检测结果: token={}", bestToken);
                return bestToken;
            }
        }
    }

    /** 在 auto-detect 模式下，从 encoder 输出检测语言并构建 initialTokens。 */
    private void ensureLanguageDetected(float[] encoderOutput) throws OrtException {
        if (!languageAuto || initialTokens != null) return;
        int detectedLang = detectLanguage(encoderOutput);
        int sot = config.decoderStartTokenId();
        this.initialTokens = new int[]{sot, detectedLang, TRANSCRIBE_TOKEN, NO_TIMESTAMPS_TOKEN};
        this.initialTokensWithTimestamps = new int[]{sot, detectedLang, TRANSCRIBE_TOKEN};
        log.info("语言检测完成: token={}", detectedLang);
    }

    // ──────────────────────────── 公开 API ────────────────────────────

    /**
     * 将音频转录为文本。
     *
     * @param audio      浮点音频样本 [-1.0, 1.0]
     * @param sampleRate 音频采样率（方法内部 resample 到 16kHz）
     * @return 转录文本
     */
    public String transcribe(float[] audio, int sampleRate) throws OrtException {
        // Whisper 只支持 30s 以内，超出部分截断防止 OOM
        int maxSamples = 30 * sampleRate;
        if (audio.length > maxSamples) {
            audio = java.util.Arrays.copyOf(audio, maxSamples);
        }

        // 1. 计算 mel 频谱
        float[][] mel = MelSpectrogram.compute(audio, sampleRate);
        if (mel[0].length > MAX_MEL_FRAMES) {
            mel = trimMel(mel, MAX_MEL_FRAMES);
        } else if (mel[0].length < MAX_MEL_FRAMES) {
            mel = padMel(mel, MAX_MEL_FRAMES);
        }

        // 2. 运行编码器
        float[] encoderOutput = runEncoder(mel);

        // 2a. 自动检测语言（auto-detect 模式）
        ensureLanguageDetected(encoderOutput);

        // 3. 自回归解码
        int[] tokens = runDecoder(encoderOutput);

        // 4. 解码为文本
        String text = tokenizer.decode(tokens);
        log.info("Whisper 转录完成: len={}s, text=\"{}\"", audio.length / sampleRate,
                text.length() > 60 ? text.substring(0, 60) + "..." : text);
        return text;
    }

    // ──────────────────────────── 带时间戳的转录 ────────────────────────────

    /**
     * 一个单词及其时间戳。
     */
    public record Word(String text, long startMs, long endMs) {
    }

    /**
     * 一个语音片段，包含文本、时间戳和单词级时间戳。
     */
    public record Segment(String text, long startMs, long endMs, List<Word> words) {
    }

    /**
     * 完整转录结果，包含全文和分段。
     */
    public record TranscriptionResult(String fullText, List<Segment> segments) {
    }

    /**
     * 将音频转录为文本，返回带单词级时间戳的分段结果。
     *
     * @param audio      浮点音频样本 [-1.0, 1.0]
     * @param sampleRate 音频采样率
     * @return 分段转录结果（含单词级时间戳）
     */
    public TranscriptionResult transcribeDetailed(float[] audio, int sampleRate) throws OrtException {
        int maxSamples = 30 * sampleRate;
        if (audio.length > maxSamples) {
            audio = java.util.Arrays.copyOf(audio, maxSamples);
        }

        float[][] mel = MelSpectrogram.compute(audio, sampleRate);
        if (mel[0].length > MAX_MEL_FRAMES) {
            mel = trimMel(mel, MAX_MEL_FRAMES);
        } else if (mel[0].length < MAX_MEL_FRAMES) {
            mel = padMel(mel, MAX_MEL_FRAMES);
        }

        float[] encoderOutput = runEncoder(mel);

        // auto-detect 模式：首次从此 encoder 输出推断语言
        ensureLanguageDetected(encoderOutput);

        if (firstDiag) {
            // 诊断：mel 统计（期望值域约 [0, 1.5]，Whisper 标准归一化后）
            double melMin = Double.MAX_VALUE, melMax = -Double.MAX_VALUE, melSum = 0;
            for (int m = 0; m < mel.length; m++)
                for (int t = 0; t < mel[m].length; t++) {
                    double v = mel[m][t];
                    if (v < melMin) melMin = v;
                    if (v > melMax) melMax = v;
                    melSum += v;
                }
            log.info("诊断 mel: shape=[{}][{}] min={} max={} mean={}",
                    mel.length, mel[0].length,
                    String.format("%.3f", melMin), String.format("%.3f", melMax),
                    String.format("%.3f", melSum / (mel.length * mel[0].length)));
            // mel 前 3 个频段、前 5 帧的具体值（用于对比 Python）
            StringBuilder melDbg = new StringBuilder("诊断 mel 样本: ");
            for (int m = 0; m < Math.min(3, mel.length); m++) {
                melDbg.append("mel[").append(m).append("]=[");
                for (int t = 0; t < Math.min(5, mel[m].length); t++) {
                    if (t > 0) melDbg.append(",");
                    melDbg.append(String.format("%.4f", mel[m][t]));
                }
                melDbg.append("] ");
            }
            log.info(melDbg.toString());
            // 诊断：encoder 输出统计（正常应无 NaN，值域约 [-2, 2]）
            double encMin = Double.MAX_VALUE, encMax = -Double.MAX_VALUE, encSum = 0;
            int encNaN = 0;
            for (float v : encoderOutput) {
                if (Float.isNaN(v) || Float.isInfinite(v)) {
                    encNaN++;
                    continue;
                }
                if (v < encMin) encMin = v;
                if (v > encMax) encMax = v;
                encSum += v;
            }
            log.info("诊断 encoder: len={} min={} max={} mean={} nan={}",
                    encoderOutput.length,
                    String.format("%.3f", encMin), String.format("%.3f", encMax),
                    String.format("%.3f", encSum / encoderOutput.length), encNaN);
        }

        // 使用标准解码器（带 no_timestamps token）获得最优文本质量
        int[] textTokens = runDecoder(encoderOutput);

        if (firstDiag) {
            firstDiag = false;
            log.info("诊断 decoder tokens: {}", java.util.Arrays.toString(textTokens));
        }

        long audioDurationMs = (long) Math.min((double) audio.length / sampleRate * 1000, 30000);
        return parseSegments(textTokens, audioDurationMs);
    }

    /**
     * 将任意时长音频转录为带单词级时间戳的分段结果。
     * <p>
     * 对 ≤30s 音频直接委托给 {@link #transcribeDetailed}。
     * 对 >30s 音频自动切成 30s 窗口（5s 重叠），使用线程池并行转录分片，
     * 片间按时间戳跳过重叠区域，避免重复文本。
     */
    public TranscriptionResult transcribeChunked(float[] audio, int sampleRate) throws OrtException {
        int chunkSamples = 30 * sampleRate;
        int shiftSamples = 25 * sampleRate;
        int totalSamples = audio.length;

        // ≤30s 走现有逻辑
        if (totalSamples <= chunkSamples) {
            return transcribeDetailed(audio, sampleRate);
        }

        int numChunks = Math.max(1, (int) Math.ceil((double) totalSamples / shiftSamples));
        int nThreads = Math.min(numChunks, Runtime.getRuntime().availableProcessors() / 3);

        // 预分配所有分片音频数据
        List<float[]> chunks = new ArrayList<>(numChunks);
        List<Long> chunkOffsets = new ArrayList<>(numChunks);
        for (int i = 0; i < numChunks; i++) {
            int start = i * shiftSamples;
            int end = Math.min(start + chunkSamples, totalSamples);
            if (start >= totalSamples) break;
            chunks.add(Arrays.copyOfRange(audio, start, end));
            chunkOffsets.add((long) start * 1000L / sampleRate);
        }
        int actualChunks = chunks.size();

        // auto-detect 模式：用第一个分片触发语言检测，确保 initialTokens 已确定
        // 之后 forkWorker 才能正确工作（worker 共享已确定的 initialTokens）
        boolean needDetect = initialTokens == null;
        int parallelStart;
        TranscriptionResult[] chunkResults;
        if (needDetect) {
            log.info("分片 [1/{}]: 首次转录触发语言检测", actualChunks);
            TranscriptionResult first = transcribeDetailed(chunks.get(0), sampleRate);
            reloadDecoderSession();
            parallelStart = 1;
            // 第一个分片结果暂存到 [0]，后续合并时一起处理
            chunkResults = new TranscriptionResult[actualChunks];
            chunkResults[0] = first;
        } else {
            parallelStart = 0;
            chunkResults = new TranscriptionResult[actualChunks];
        }

        // 线程池并行转录剩余分片
        int parallelCount = actualChunks - parallelStart;
        if (parallelCount > 0) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelCount, nThreads));
            for (int i = parallelStart; i < actualChunks; i++) {
                final int idx = i;
                final float[] chunkAudio = chunks.get(i);
                pool.submit(() -> {
                    long t0 = System.currentTimeMillis();
                    log.info("分片 [{}/{}]: offset={}ms, samples={} (thread={})",
                            idx + 1, actualChunks, chunkOffsets.get(idx), chunkAudio.length,
                            Thread.currentThread().getName());
                    try (WhisperModel worker = forkWorker()) {
                        TranscriptionResult r = worker.transcribeDetailed(chunkAudio, sampleRate);
                        long elapsed = System.currentTimeMillis() - t0;
                        log.info("分片 [{}/{}] 完成: {}s (segments={}, words={}, text=\"{}\")",
                                idx + 1, actualChunks, elapsed / 1000, r.segments().size(),
                                r.segments().stream().mapToInt(s -> s.words().size()).sum(),
                                r.fullText.length() > 50 ? r.fullText.substring(0, 50) + "..." : r.fullText);
                        chunkResults[idx] = r;
                    } catch (Exception e) {
                        throw new RuntimeException("分片 " + idx + " 转录失败", e);
                    }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OrtException("分片转录被中断");
            }
        }

        // 按序合并所有分片结果
        List<Segment> allSegments = mergeSegments(actualChunks, chunkResults, chunkOffsets);

        String fullText = allSegments.stream()
                .map(Segment::text)
                .collect(java.util.stream.Collectors.joining(" "));

        log.info("Whisper 分片转录完成: chunks={}, threads={}, total_segments={}, duration={}s",
                actualChunks, nThreads, allSegments.size(), totalSamples / sampleRate);
        return new TranscriptionResult(fullText, allSegments);
    }

    private static List<Segment> mergeSegments(int actualChunks, TranscriptionResult[] chunkResults, List<Long> chunkOffsets) {
        List<Segment> allSegments = new ArrayList<>();
        long coveredUntilMs = 0;
        for (int i = 0; i < actualChunks; i++) {
            TranscriptionResult chunkResult = chunkResults[i];
            long chunkOffsetMs = chunkOffsets.get(i);
            if (chunkResult == null) continue;
            for (Segment seg : chunkResult.segments()) {
                long segEndMs = seg.endMs() + chunkOffsetMs;
                if (segEndMs <= coveredUntilMs) continue;

                List<Word> adjWords = new ArrayList<>();
                for (Word w : seg.words()) {
                    long ws = w.startMs() + chunkOffsetMs;
                    long we = w.endMs() + chunkOffsetMs;
                    if (we <= coveredUntilMs) continue;
                    if (ws < coveredUntilMs) ws = coveredUntilMs;
                    adjWords.add(new Word(w.text(), ws, we));
                }

                if (!adjWords.isEmpty()) {
                    StringBuilder text = new StringBuilder();
                    for (Word w : adjWords) {
                        if (text.length() > 0) text.append(" ");
                        text.append(w.text());
                    }
                    long actualStart = adjWords.get(0).startMs();
                    long actualEnd = adjWords.get(adjWords.size() - 1).endMs();
                    allSegments.add(new Segment(text.toString(), actualStart, actualEnd, adjWords));
                    coveredUntilMs = actualEnd;
                }
            }
        }
        return allSegments;
    }

    private int[] runDecoderRaw(float[] encoderState) throws OrtException {
        int[] init = initialTokensWithTimestamps;
        int[] tokens = init.clone();
        int maxLen = Math.min(config.maxTargetPositions(), 128);
        int firstTs = config.noTimestampsTokenId() + 1;
        int lastTs = config.vocabSize() - 1;
        int prevTsPos = -1;

        java.util.ArrayDeque<Integer> recentTokens = new java.util.ArrayDeque<>(REPEAT_WINDOW);

        for (int pos = init.length; pos < maxLen; pos++) {
            int lastToken = decodeStepFull(tokens, encoderState, recentTokens);
            if (lastToken == eotToken || lastToken < 0) break;

            boolean isTs = lastToken >= firstTs && lastToken <= lastTs;
            if (isTs) {
                if (prevTsPos >= 0 && pos - prevTsPos > 16) break;
                prevTsPos = pos;
            }

            tokens = Arrays.copyOf(tokens, pos + 1);
            tokens[pos] = lastToken;
            addRecentToken(recentTokens, lastToken);
        }

        int start = init.length;
        int end = tokens.length;
        for (int i = start; i < tokens.length; i++) {
            if (tokens[i] == eotToken) {
                end = i;
                break;
            }
        }

        return Arrays.copyOfRange(tokens, start, end);
    }

    private TranscriptionResult parseSegments(int[] tokens, long audioDurationMs) {
        int firstTs = config.noTimestampsTokenId() + 1;
        double tsStep = 30.0 / (config.vocabSize() - firstTs);
        List<Segment> segments = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        double maxSec = Math.min(audioDurationMs / 1000.0, 30.0);

        int i = 0;
        while (i < tokens.length) {
            while (i < tokens.length && tokens[i] >= firstTs) i++;
            if (i >= tokens.length) break;

            int textStart = i;
            while (i < tokens.length && tokens[i] < firstTs) i++;
            int textEnd = i;

            double startSec = 0, endSec = maxSec;
            if (textStart > 0 && tokens[textStart - 1] >= firstTs) {
                startSec = (tokens[textStart - 1] - firstTs) * tsStep;
            }
            if (textEnd < tokens.length && tokens[textEnd] >= firstTs) {
                endSec = (tokens[textEnd] - firstTs) * tsStep;
            }

            long startMs = (long) (startSec * 1000);
            long endMs = (long) (endSec * 1000);

            int[] textTokens = Arrays.copyOfRange(tokens, textStart, textEnd);
            String segText = tokenizer.decode(textTokens).trim();
            if (segText.isEmpty()) continue;

            // 按文本字符比例分配单词起止时间
            String[] words = segText.split("\\s+");
            List<Word> wordList = new ArrayList<>();
            if (words.length > 1) {
                long totalChars = 0;
                for (String w : words) totalChars += w.length();
                long charOffset = 0;
                for (String w : words) {
                    long wStart = startMs + (endMs - startMs) * charOffset / Math.max(totalChars, 1);
                    charOffset += w.length();
                    long wEnd = startMs + (endMs - startMs) * charOffset / Math.max(totalChars, 1);
                    wordList.add(new Word(w, wStart, wEnd));
                }
            } else if (words.length == 1) {
                wordList.add(new Word(words[0], startMs, endMs));
            }

            segments.add(new Segment(segText, startMs, endMs, wordList));
            if (fullText.length() > 0) fullText.append(" ");
            fullText.append(segText);
        }

        // 如果没有分段（纯时间戳或无输出），回退为全文
        if (segments.isEmpty()) {
            String text = tokenizer.decode(tokens).trim();
            if (!text.isEmpty()) {
                segments.add(new Segment(text, 0, 0, List.of()));
            }
        }

        return new TranscriptionResult(fullText.toString(), segments);
    }

    @Override
    public void close() {
        try {
            if (encoderSession != null) encoderSession.close();
            if (decoderSession != null) decoderSession.close();
        } catch (OrtException e) {
            log.warn("关闭 Whisper ONNX session 失败", e);
        }
    }

    /** 重建 decoder session，彻底释放 native 内存（每片后调用） */
    public void reloadDecoderSession() throws OrtException {
        if (decoderSession != null) {
            try { decoderSession.close(); } catch (Exception e) { /* ignore */ }
            decoderSession = null;
        }
        decoderSession = loadSession(config.decoderModelPath());
    }

    // ──────────────────────────── 编码器 ────────────────────────────

    private float[] runEncoder(float[][] mel) throws OrtException {
        int nMels = mel.length;
        int nFrames = mel[0].length;

        float[] flat = new float[nMels * nFrames];
        for (int m = 0; m < nMels; m++) {
            System.arraycopy(mel[m], 0, flat, m * nFrames, nFrames);
        }

        long[] inputShape = {1, nMels, nFrames};
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(flat), inputShape)) {
            OrtSession.Result result = encoderSession.run(
                    Collections.singletonMap(encoderInputName, inputTensor));
            OnnxValue value = result.get(encoderOutputName)
                    .orElseThrow(() -> new OrtException("编码器输出缺失: " + encoderOutputName));
            try (OnnxTensor output = (OnnxTensor) value) {
                return output.getFloatBuffer().array();
            }
        }
    }

    // ──────────────────────────── 解码器（自回归） ────────────────────────────

    private int[] runDecoder(float[] encoderState) throws OrtException {
        if (usePastCache) {
            return runDecoderCache(encoderState);
        }
        return runDecoderFull(encoderState);
    }

    // ──────────────────── KV-cache 增量推理 ────────────────────

    private int[] runDecoderCache(float[] encoderState) throws OrtException {
        java.util.List<Integer> generated = new java.util.ArrayList<>();
        boolean diag = firstDiag;

        FloatBuffer encBuf = FloatBuffer.wrap(encoderState);
        int srcLen = encoderState.length / config.dModel();
        long[] encShape = {1, srcLen, config.dModel()};

        OrtSession.Result prevResult = null;

        // 重复惩罚滑动窗口
        java.util.ArrayDeque<Integer> recentTokens = new java.util.ArrayDeque<>(REPEAT_WINDOW);

        try {
            // 第一步：完整初始序列 + 空 past（seqLen=0）+ use_cache_branch=true
            long[] initIds = new long[initialTokens.length];
            for (int i = 0; i < initialTokens.length; i++) initIds[i] = initialTokens[i];

            Map<String, OnnxTensorLike> inputs = new LinkedHashMap<>();
            inputs.put(decoderInputIdsName, OnnxTensor.createTensor(env,
                    LongBuffer.wrap(initIds), new long[]{1, initIds.length}));
            inputs.put(decoderEncoderStateName, OnnxTensor.createTensor(env,
                    encBuf.duplicate(), encShape));

            // 空 past tensors：shape [1, num_heads, 0, head_dim]，让模型走 cache 路径但从头计算
            java.util.List<OnnxTensor> emptyPastTensors = new java.util.ArrayList<>();
            if (useCacheBranchName != null) {
                inputs.put(useCacheBranchName, OnnxTensor.createTensor(env, new boolean[]{true}));
                int numHeads = config.decoderAttentionHeads();
                int headDim = config.dModel() / numHeads;
                long[] emptyShape = {1, numHeads, 0, headDim};
                for (String pastName : pastInputNames) {
                    OnnxTensor emptyTensor = OnnxTensor.createTensor(env,
                            FloatBuffer.allocate(0), emptyShape);
                    inputs.put(pastName, emptyTensor);
                    emptyPastTensors.add(emptyTensor);
                }
            }

            prevResult = decoderSession.run(inputs);
            closeOwnedInputs(inputs);
            for (OnnxTensor t : emptyPastTensors) safeClose(t);

            int token = getLogitsToken(prevResult, initialTokens.length, recentTokens, REPEAT_PENALTY);
            if (diag) logDiagStep(0, token);
            if (token == eotToken || token < 0) return new int[0];
            generated.add(token);
            addRecentToken(recentTokens, token);

            // 后续步：只传新 token + past（从 prevResult 的 present 输出获取）
            int maxLen = Math.min(config.maxTargetPositions(), 448);
            for (int step = 1; step < maxLen; step++) {
                long[] ids = new long[]{token};

                Map<String, OnnxTensorLike> nextInputs = new LinkedHashMap<>();
                nextInputs.put(decoderInputIdsName, OnnxTensor.createTensor(env,
                        LongBuffer.wrap(ids), new long[]{1, 1}));
                nextInputs.put(decoderEncoderStateName, OnnxTensor.createTensor(env,
                        encBuf.duplicate(), encShape));
                if (useCacheBranchName != null) {
                    nextInputs.put(useCacheBranchName, OnnxTensor.createTensor(env, new boolean[]{true}));
                }

                // past inputs: 从 prevResult 获取 present 输出（按名称匹配，非索引）
                for (String presentName : presentOutputNames) {
                    String pastName = presentName.replace("present", "past_key_values");
                    nextInputs.put(pastName,
                            (OnnxTensor) prevResult.get(presentName).orElseThrow());
                }

                OrtSession.Result newResult = decoderSession.run(nextInputs);

                closeResult(prevResult);      // 关闭旧 Result（含旧 present values）
                closeOwnedInputs(nextInputs); // 关闭新创建的 input_ids/enc tensors
                prevResult = newResult;

                token = getLogitsToken(prevResult, 1, recentTokens, REPEAT_PENALTY);
                if (diag && step < 8) logDiagStep(step, token);
                if (token == eotToken || token < 0) break;
                generated.add(token);
                addRecentToken(recentTokens, token);
            }

            return generated.stream().mapToInt(Integer::intValue).toArray();
        } finally {
            closeResult(prevResult);
        }
    }

    /**
     * 从 Result 的 logits 输出中选取 best token（考虑 suppress 规则 + 重复惩罚）。
     *
     * @param recentTokens 最近生成的 token ID 集合，用于重复惩罚（可为空）
     * @param repeatPenalty 重复惩罚系数（>1.0 惩罚重复，1.0 无惩罚）
     */
    private int getLogitsToken(OrtSession.Result result, int seqLen,
                               Collection<Integer> recentTokens, float repeatPenalty) throws OrtException {
        OnnxTensor logitsTensor = (OnnxTensor) result.get(decoderLogitsName).orElseThrow();
        float[] logits = logitsTensor.getFloatBuffer().array();
        int vocabSize = config.vocabSize();
        int offset = (seqLen - 1) * vocabSize;

        int best = 0;
        float bestVal = Float.NEGATIVE_INFINITY;
        boolean firstStep = seqLen == initialTokens.length;
        for (int v = 0; v < vocabSize; v++) {
            if (firstStep && config.isBeginSuppressToken(v)) continue;
            if (config.isSuppressToken(v)) continue;
            float score = logits[offset + v];
            if (repeatPenalty > 1.0f && recentTokens.contains(v)) {
                score /= repeatPenalty;
            }
            if (score > bestVal) {
                bestVal = score;
                best = v;
            }
        }
        return best;
    }

    /** 无重复惩罚版本（兼容旧调用）。 */
    private int getLogitsToken(OrtSession.Result result, int seqLen) throws OrtException {
        return getLogitsToken(result, seqLen, Collections.emptyList(), 1.0f);
    }

    private static void addRecentToken(java.util.ArrayDeque<Integer> deque, int token) {
        if (deque.size() >= REPEAT_WINDOW) {
            deque.removeFirst();
        }
        deque.addLast(token);
    }

    private void closeOwnedInputs(Map<String, OnnxTensorLike> inputs) {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(decoderInputIdsName);
        names.add(decoderEncoderStateName);
        if (useCacheBranchName != null) names.add(useCacheBranchName);
        for (String name : names) {
            OnnxTensorLike t = inputs.get(name);
            if (t != null) safeClose(t);
        }
    }

    private void closeResult(OrtSession.Result r) {
        safeClose(r);
    }

    private void logDiagStep(int step, int token) {
        String tokStr = (token == eotToken) ? "<EOT>" : tokenizer.tokenToString(token);
        log.info("诊断 decoder step {}: token={} ({})", step, token, tokStr);
    }

    // ──────────────────── 全量自回归（无 KV-cache 回退） ────────────────────
    // 重复惩罚系数，>1.0 降低重复概率。1.1 对重复 token 除 1.1，抑制重复但不过度激进。
    private static final float REPEAT_PENALTY = 1.1f;
    // 重复惩罚窗口大小（最近 N 个 token 计入惩罚）
    private static final int REPEAT_WINDOW = 10;

    private int[] runDecoderFull(float[] encoderState) throws OrtException {
        int[] tokens = initialTokens.clone();
        int maxLen = Math.min(config.maxTargetPositions(), 128);
        boolean diag = firstDiag;

        // 滑动窗口记录最近 token，用于重复惩罚
        java.util.ArrayDeque<Integer> recentTokens = new java.util.ArrayDeque<>(REPEAT_WINDOW);

        for (int pos = initialTokens.length; pos < maxLen; pos++) {
            int lastToken = decodeStepFull(tokens, encoderState, recentTokens);
            if (diag && pos < initialTokens.length + 8) {
                logDiagStep(pos - initialTokens.length, lastToken);
            }
            if (lastToken == eotToken || lastToken < 0) break;
            tokens = Arrays.copyOf(tokens, pos + 1);
            tokens[pos] = lastToken;
            addRecentToken(recentTokens, lastToken);
        }

        int start = initialTokens.length;
        int end = tokens.length;
        for (int i = start; i < tokens.length; i++) {
            if (tokens[i] == eotToken) {
                end = i;
                break;
            }
        }
        return Arrays.copyOfRange(tokens, start, end);
    }

    private int decodeStepFull(int[] inputTokens, float[] encoderState,
                               java.util.ArrayDeque<Integer> recentTokens) throws OrtException {
        int seqLen = inputTokens.length;
        long[] inputIds = new long[seqLen];
        for (int i = 0; i < seqLen; i++) inputIds[i] = inputTokens[i];

        int srcLen = encoderState.length / config.dModel();
        long[] encShape = {1, srcLen, config.dModel()};

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), new long[]{1, seqLen});
             OnnxTensor encTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(encoderState), encShape)) {
            Map<String, OnnxTensorLike> inputs = new LinkedHashMap<>();
            inputs.put(decoderInputIdsName, idsTensor);
            inputs.put(decoderEncoderStateName, encTensor);
            try (OrtSession.Result result = decoderSession.run(inputs)) {
                return getLogitsToken(result, seqLen, recentTokens, REPEAT_PENALTY);
            }
        }
    }

    private static void safeClose(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ──────────────────────────── 辅助方法 ────────────────────────────

    private static float[][] trimMel(float[][] mel, int maxFrames) {
        int nMels = mel.length;
        float[][] trimmed = new float[nMels][maxFrames];
        for (int m = 0; m < nMels; m++) {
            System.arraycopy(mel[m], 0, trimmed[m], 0, maxFrames);
        }
        return trimmed;
    }

    private static float[][] padMel(float[][] mel, int targetFrames) {
        int nMels = mel.length;
        int srcFrames = mel[0].length;
        float[][] padded = new float[nMels][targetFrames];
        for (int m = 0; m < nMels; m++) {
            System.arraycopy(mel[m], 0, padded[m], 0, srcFrames);
        }
        return padded;
    }

    private static OrtSession loadSession(Path modelPath) throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        opts.setCPUArenaAllocator(false);
        opts.setMemoryPatternOptimization(false);
        return OrtEnvironment.getEnvironment().createSession(modelPath.toString(), opts);
    }

    private static String discoverInputName(OrtSession session, String... candidates)
            throws OrtException {
        var info = session.getInputInfo();
        for (String c : candidates) {
            if (info.containsKey(c)) return c;
        }
        // fallback: 返回第一个输入名称
        return info.keySet().iterator().next();
    }

    private static String discoverOutputName(OrtSession session, String... candidates)
            throws OrtException {
        var info = session.getOutputInfo();
        for (String c : candidates) {
            if (info.containsKey(c)) return c;
        }
        return info.keySet().iterator().next();
    }

    @Override
    public String toString() {
        return String.format("initial_tokens=%s eot=%d",
                java.util.Arrays.toString(initialTokens), eotToken);
    }
}
