package site.dengwei.onnxruntime.whisper;

import ai.onnxruntime.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

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

    // 模型目录和语言设置（从 loadOrDownload 到构造器的透传）
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
    // 反向映射：token ID → 语言代码，用于 initial prompt 按语言查找
    private final Map<Integer, String> reverseLangTokens;

    // 诊断标志：仅第一个 chunk 打印详细统计
    private volatile boolean firstDiag = true;

    // KV-cache 增量推理（仅 decoder_with_past_model.onnx 支持）
    private final boolean usePastCache;
    private final List<String> pastInputNames;    // past_key_values.0.decoder.key, ...
    private final List<String> presentOutputNames; // present.0.decoder.key, ...
    private final String useCacheBranchName;       // use_cache_branch（merged 模型特有，null=非 merged）

    // 特殊 token ID 从 config 动态读取（每个模型的 forced_decoder_ids 可能不同）
    // 不要硬编码——whisper-tiny.en 用 50362，其他模型可能用 50359

    /**
     * 各语言 optional initial prompt。
     *
     * initial prompt 是 Whisper 标准功能：token 作为"已生成上下文"前置到 decoder 输入。
     * 它能让模型更好地预测标点和风格，但 prompt 文本过长或与音频内容无关时会被模型
     * 当成"前面说过的话"来续写，导致严重幻觉。
     *
     * 经验：
     * - 中文 Whisper 模型天然缺少标点，简短提示有帮助
     * - 英文/日文等语言模型标点能力较强，不需要 prompt
     * - prompt 必须简短（≤5 词）且不含具体内容词语
     * - 含"dubbing voice"等具体内容词会直接泄漏到输出
     *
     * 未列出的语言不添加提示。
     */
    private static final Map<String, String> INITIAL_PROMPTS = Map.of(
            "zh", "请添加标点符号"
    );

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
        this(modelDir, language, DEFAULT_REPEAT_PENALTY, DEFAULT_REPEAT_WINDOW);
    }

    /**
     * @param modelDir      包含 Whisper ONNX 模型和词表的目录
     * @param language      目标语言代码（如 "en", "zh"）
     * @param repeatPenalty 重复惩罚系数（>1.0 抑制重复 token，默认 1.2）
     * @param repeatWindow  重复惩罚窗口大小（最近 N 个 token，默认 20）
     */
    public WhisperModel(Path modelDir, String language, float repeatPenalty, int repeatWindow) {
        this.repeatPenalty = repeatPenalty;
        this.repeatWindow = repeatWindow;
        this.env = OrtEnvironment.getEnvironment();
        this.modelDir = modelDir;
        this.language = language;
        this.config = new WhisperConfig(modelDir);
        this.tokenizer = new WhisperTokenizer(modelDir);

        // 语言 token 映射：优先从 generation_config.json 动态读取，回退到硬编码常用语言
        this.langTokens = loadLangTokens(modelDir);
        this.reverseLangTokens = new java.util.HashMap<>(langTokens.size());
        for (var entry : langTokens.entrySet()) {
            reverseLangTokens.put(entry.getValue(), entry.getKey());
        }

        // 自动检测语言模式：language 为 null/blank/"auto" 时启用
        // 首次转录时 detectLanguage 会从 encoder 输出推断语言，然后才构建 initialTokens
        boolean autoDetect = language == null || language.isBlank() || "auto".equalsIgnoreCase(language);
        this.languageAuto = autoDetect;

        try {
            this.encoderSession = loadSession(config.encoderModelPath());
            // 优先加载 merged decoder（含 KV-cache，ORT 1.22+ 可用），
            // 不存在时回退标准 decoder（无 KV-cache，性能慢 ~14 倍）。
            Path merged = config.decoderMergedPath();
            if (merged != null && Files.exists(merged)) {
                this.decoderSession = loadSession(merged);
                log.info("使用 merged decoder (KV-cache): {}", merged.getFileName());
            } else {
                this.decoderSession = loadSession(config.decoderModelPath());
                log.warn("merged decoder 不存在，回退标准 decoder（无 KV-cache）: {}",
                        config.decoderModelPath().getFileName());
            }

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
            if (languageAuto) {
                // auto-detect 模式：initialTokens 在首次 detectLanguage 后构建
                this.initialTokens = null;
                this.initialTokensWithTimestamps = null;
            } else {
                // 对于多语言模型，将默认语言替换为目标语言
                int langTokenId = resolveLangToken(language, langTokens);
                config.overrideLanguage(langTokenId);
                int[] promptTokens = tokenizeInitialPrompt(language);
                this.initialTokens = buildInitialTokens(config.initialDecoderTokens(), promptTokens);
                this.initialTokensWithTimestamps = buildInitialTokens(config.initialDecoderTokensWithTimestamps(), promptTokens);
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
        config.overrideLanguage(detectedLang);
        String langCode = decodeLanguageToken(detectedLang);
        int[] promptTokens = tokenizeInitialPrompt(langCode);
        this.initialTokens = buildInitialTokens(config.initialDecoderTokens(), promptTokens);
        this.initialTokensWithTimestamps = buildInitialTokens(config.initialDecoderTokensWithTimestamps(), promptTokens);
        log.info("语言检测完成: token={} lang={}", detectedLang, langCode);
    }

    /** 构建带 initial prompt 的初始 token 序列。 */
    private int[] buildInitialTokens(int[] baseTokens, int[] promptTokens) {
        int[] tokens = Arrays.copyOf(baseTokens, baseTokens.length + promptTokens.length);
        System.arraycopy(promptTokens, 0, tokens, baseTokens.length, promptTokens.length);
        return tokens;
    }

    /** 按语言代码获取 initial prompt 的 token 序列。无提示时返回空数组。 */
    private int[] tokenizeInitialPrompt(String langCode) {
        if (langCode == null) return new int[0];
        String prompt = INITIAL_PROMPTS.get(langCode);
        if (prompt == null || prompt.isBlank()) return new int[0];
        try {
            return tokenizer.encode(prompt);
        } catch (Exception e) {
            log.warn("initialPrompt 编码失败 (lang={}): {}，跳过", langCode, e.getMessage());
            return new int[0];
        }
    }

    /** 将语言 token ID 反查为语言代码（用于按语言查 initial prompt）。 */
    private String decodeLanguageToken(int tokenId) {
        String lang = reverseLangTokens.get(tokenId);
        return lang != null ? lang : "en";
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

        // 使用时间戳解码路径（initialTokensWithTimestamps→模型输出时间戳 token→parseSegments 正确解析时间边界）
        int[] textTokens = runDecoder(encoderOutput, initialTokensWithTimestamps);

        if (firstDiag) {
            firstDiag = false;
            log.info("诊断 decoder tokens: {}", java.util.Arrays.toString(textTokens));
        }

        long audioDurationMs = (long) Math.min((double) audio.length / sampleRate * 1000, 30000);
        TranscriptionResult result = parseSegments(textTokens, audioDurationMs);

        // 对齐 faster-whisper: no_speech 检测 — 音频够长但输出过短 → 静音段，空结果
        if (audioDurationMs > 5000 && result.segments().size() <= 1) {
            int meaningfulWords = 0;
            for (Segment seg : result.segments()) {
                for (Word w : seg.words()) {
                    String t = w.text().trim();
                    if (!t.isEmpty() && !isAllPunctuation(t)) meaningfulWords++;
                }
            }
            if (meaningfulWords <= 2) {
                log.info("no_speech 检测触发: audio={}ms, segments={}, meaningfulWords={}, 返回空",
                        audioDurationMs, result.segments().size(), meaningfulWords);
                return new TranscriptionResult("", List.of());
            }
        }

        return result;
    }

    // 最长单次转录时长（Whisper 模型限制）
    private static final int MAX_CHUNK_SEC = 30;
    // 分片间重叠（秒），用于对齐分片边界
    private static final int CHUNK_SHIFT_SEC = 25;

    /**
     * 将任意时长音频转录为带单词级时间戳的分段结果。
     * <p>
     * 对 ≤30s 音频直接委托给 {@link #transcribeDetailed}。
     * 对 >30s 音频先尝试静音点分片（对齐 Python faster-whisper），每个分片 = 连续语音段；
     * 如果无 ≥2s 静音或静音点不足则回退到固定 30s 窗口。
     * 所有分片无论来自静音分割还是固定窗口，都会经过
     * {@link #ensureMaxChunkSize(List, List, int)} 保证每个分片 ≤30s。
     */
    public TranscriptionResult transcribeChunked(float[] audio, int sampleRate) throws OrtException {
        int totalSamples = audio.length;
        int maxChunkSamples = MAX_CHUNK_SEC * sampleRate;

        if (totalSamples <= maxChunkSamples) {
            return transcribeDetailed(audio, sampleRate);
        }

        // 尝试静音点分片
        List<float[]> chunks = new ArrayList<>();
        List<Long> chunkOffsets = new ArrayList<>();
        List<int[]> silenceRegions = WhisperVad.detectSilenceRegions(audio, sampleRate);
        boolean useSilenceChunks = WhisperVad.buildSilenceChunks(audio, sampleRate, silenceRegions, chunks, chunkOffsets);

        if (!useSilenceChunks) {
            // 回退到固定 30s 窗口（5s 重叠）
            buildFixedChunks(audio, sampleRate, chunks, chunkOffsets);
        }

        // 核心修复：保证每个分片 ≤ MAX_CHUNK_SEC
        // 静音分片产生的 chunk 可能远大于 30s（因为两个 split point 之间可能跨度达数分钟），
        // 而 transcribeDetailed() 内部会静默截断到 30s，导致尾部丢失。
        ensureMaxChunkSize(chunks, chunkOffsets, sampleRate);

        int actualChunks = chunks.size();

        // 串行处理第一个分片（auto-detect 模式下触发语言检测并确定 initialTokens）。
        // 注意：不再调用 reloadDecoderSession——本方法现在共享同一组 encoder/decoder
        // session 处理所有分片，reload 会关闭正在被其他线程使用的 session。
        TranscriptionResult[] chunkResults = new TranscriptionResult[actualChunks];
        chunkResults[0] = transcribeDetailed(chunks.get(0), sampleRate);

        // 剩余分片：串行转录，共享本实例同一组 encoder/decoder session。
        // 单 worker 串行执行：避免多线程并发 transcribeDetailed 时每个线程各占一份
        // workspace 加剧原生内存占用，而 ONNX 推理内部（intra-op）已多线程并行。
        for (int i = 1; i < actualChunks; i++) {
            long t0 = System.currentTimeMillis();
            log.info("分片 [{}/{}]: offset={}ms, samples={}",
                    i + 1, actualChunks, chunkOffsets.get(i), chunks.get(i).length);
            try {
                TranscriptionResult r = transcribeDetailed(chunks.get(i), sampleRate);
                long elapsed = System.currentTimeMillis() - t0;
                log.info("分片 [{}/{}] 完成: {}s (segments={}, words={})",
                        i + 1, actualChunks, elapsed / 1000,
                        r.segments().size(),
                        r.segments().stream().mapToInt(s -> s.words().size()).sum());
                chunkResults[i] = r;
            } catch (Exception e) {
                throw new RuntimeException("分片 " + i + " 转录失败", e);
            }
        }

        // 诊断：每个分片的原始转录结果
        for (int i = 0; i < actualChunks; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append("分片诊断 [").append(i + 1).append("/").append(actualChunks).append("]");
            sb.append(" offset=").append(chunkOffsets.get(i)).append("ms");
            if (chunkResults[i] == null) {
                sb.append(" result=null");
            } else {
                var r = chunkResults[i];
                sb.append(" segments=").append(r.segments().size());
                int wc = r.segments().stream().mapToInt(s -> s.words().size()).sum();
                sb.append(" words=").append(wc);
                String text = r.fullText();
                if (text.length() > 120) text = text.substring(0, 120) + "...";
                sb.append(" text=\"").append(text).append("\"");
                if (!r.segments().isEmpty()) {
                    var last = r.segments().get(r.segments().size() - 1);
                    long lastAbsEnd = last.endMs() + chunkOffsets.get(i);
                    sb.append(" lastSegEnd=").append(lastAbsEnd).append("ms");
                }
            }
            log.warn(sb.toString());
        }

        // 按序合并所有分片结果
        List<Segment> allSegments = mergeSegments(actualChunks, chunkResults, chunkOffsets);

        // 合并短碎片：≤3 词且无句尾标点的 segment 并入前一段
        allSegments = mergeFragments(allSegments);
        // 过滤垃圾段：纯标点/噪声/重复（在 mergeFragments 之后执行，避免短 fragment 被误删）
        allSegments = filterGarbageSegments(allSegments);

        String fullText = allSegments.stream()
                .map(Segment::text)
                .collect(java.util.stream.Collectors.joining(" "));

        log.info("Whisper 分片转录完成: chunks={}, total_segments={}, duration={}s",
                actualChunks, allSegments.size(), totalSamples / sampleRate);
        return new TranscriptionResult(fullText, allSegments);
    }

    /**
     * 构建固定 30s 窗口（5s 重叠）分片。
     * 与 faster-whisper 的固定窗口策略一致。
     * 最后一个分片不足 40% 窗口大小时前移起始位置，避免短分片 pad 大量静音诱导幻觉。
     */
    private static void buildFixedChunks(float[] audio, int sampleRate,
                                          List<float[]> chunksOut, List<Long> offsetsOut) {
        int maxChunkSamples = MAX_CHUNK_SEC * sampleRate;
        int shiftSamples = CHUNK_SHIFT_SEC * sampleRate;
        int totalSamples = audio.length;
        int minSamples = (int) (maxChunkSamples * 0.4);
        int numChunks = Math.max(1, (int) Math.ceil((double) totalSamples / shiftSamples));
        for (int i = 0; i < numChunks; i++) {
            int start = i * shiftSamples;
            int end = Math.min(start + maxChunkSamples, totalSamples);
            if (start >= totalSamples) break;
            // 最后一个分片太短 → 前移起始位置，保证至少 minSamples 有效音频
            if (i == numChunks - 1 && (end - start) < minSamples && i > 0) {
                start = Math.max(0, end - minSamples);
            }
            chunksOut.add(Arrays.copyOfRange(audio, start, end));
            offsetsOut.add((long) start * 1000L / sampleRate);
        }
    }

    

    /**
     * 保证所有分片不超过 {@link #MAX_CHUNK_SEC}。
     * <p>
     * 静音分片可能产生任意长度的 chunk（两个 split point 之间可能跨度达数分钟），
     * 而 {@link #transcribeDetailed(float[], int)} 会静默截断到 30s 导致尾部丢失。
     * 本方法将 >30s 的 chunk 拆分为多个 30s 窗口（5s 重叠）。
     */
    private static void ensureMaxChunkSize(List<float[]> chunks, List<Long> offsets, int sampleRate) {
        int maxSamples = MAX_CHUNK_SEC * sampleRate;
        int shiftSamples = CHUNK_SHIFT_SEC * sampleRate;
        // 最小有效音频量：低于此值的分片会被 pad 大量静音 → 诱发幻觉
        int minSamples = (int) (maxSamples * 0.4);
        List<float[]> newChunks = new ArrayList<>();
        List<Long> newOffsets = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            float[] chunk = chunks.get(i);
            long offsetMs = offsets.get(i);
            if (chunk.length <= maxSamples) {
                newChunks.add(chunk);
                newOffsets.add(offsetMs);
            } else {
                int numSub = Math.max(1, (chunk.length + shiftSamples - 1) / shiftSamples);
                for (int j = 0; j < numSub; j++) {
                    int subStart = j * shiftSamples;
                    int subEnd = Math.min(subStart + maxSamples, chunk.length);
                    if (subStart >= chunk.length) break;
                    // 最后一个子分片不足 minSamples → 向后扩展覆盖更多有效音频
                    if (j == numSub - 1 && (subEnd - subStart) < minSamples) {
                        subStart = Math.max(0, subEnd - minSamples);
                    }
                    newChunks.add(Arrays.copyOfRange(chunk, subStart, subEnd));
                    newOffsets.add(offsetMs + (long) subStart * 1000L / sampleRate);
                }
            }
        }
        chunks.clear();
        chunks.addAll(newChunks);
        offsets.clear();
        offsets.addAll(newOffsets);
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

    /**
     * 过滤垃圾 segment：纯标点单 segment、重复文本。
     * <p>
     * 注意：不做尾部内容截断——ASR 输出的末尾单词（如 "Thank you"）是有效内容，
     * 不应因缺少句尾标点或长度短而被删除。
     */
    private static List<Segment> filterGarbageSegments(List<Segment> segments) {
        List<Segment> filtered = new ArrayList<>();
        Set<String> seenTexts = new java.util.HashSet<>();

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            String text = seg.text().trim().toLowerCase();
            if (text.isEmpty()) continue;

            // 单 word 纯标点 → 噪声，删
            if (seg.words() != null && seg.words().size() == 1
                    && isAllPunctuation(text)) continue;

            // 与之前 segment 文本相同且时间接近 → 去重
            if (seenTexts.contains(text) && isLikelyDuplicate(seg, segments, i, text)) continue;

            filtered.add(seg);
            seenTexts.add(text);
        }
        return filtered;
    }

    /** 判断 segment 是否为重复：与之前某段文本相同且时间接近。 */
    private static boolean isLikelyDuplicate(Segment seg, List<Segment> all, int idx, String text) {
        for (int j = idx - 1; j >= 0 && j >= idx - 5; j--) {
            Segment prev = all.get(j);
            if (prev.text().trim().equalsIgnoreCase(text)) {
                long gap = Math.abs(seg.startMs() - prev.endMs());
                if (gap < 8000) return true;
            }
        }
        return false;
    }

    /** 合并短碎片 segment：≤3 词且无句尾标点的段并入前一段（减少过度碎片化）。 */
    private static List<Segment> mergeFragments(List<Segment> segments) {
        if (segments.size() < 2) return segments;
        List<Segment> merged = new ArrayList<>();
        for (Segment seg : segments) {
            if (merged.isEmpty()) {
                merged.add(seg);
                continue;
            }
            String text = seg.text().trim();
            String[] words = text.split("\\s+");
            boolean isShort = words.length <= 3 && !hasSentenceEnding(text);
            if (isShort) {
                Segment last = merged.remove(merged.size() - 1);
                String combined = last.text() + " " + text;
                List<Word> combinedWords = new ArrayList<>(last.words());
                combinedWords.addAll(seg.words());
                long endMs = Math.max(last.endMs(), seg.endMs());
                merged.add(new Segment(combined, last.startMs(), endMs, combinedWords));
            } else {
                merged.add(seg);
            }
        }
        return merged;
    }

    /** 判断字符串是否全部由标点/空白组成（含中英文标点）。 */
    private static boolean isAllPunctuation(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c == '.' || c == ',' || c == '!' || c == '?'
                    || c == ';' || c == ':' || c == '"' || c == '\''
                    || c == ' ' || c == '-' || c == '\n'
                    || c == '。' || c == '，' || c == '！' || c == '？'
                    || c == '；' || c == '：' || c == '、'
                    || c == '…' || c == '~')) {
                return false;
            }
        }
        return true;
    }

    /** 判断文本是否以句尾标点结尾（含常见语言）。 */
    private static boolean hasSentenceEnding(String text) {
        if (text.isEmpty()) return false;
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?'
                || last == '。' || last == '！' || last == '？'
                || last == '…' || last == '~';
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

            // 跳过纯标点段（静音/噪声末尾模型会产出 "." 等）
            if (isAllPunctuation(segText)) continue;

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

    /** 重建 decoder session，彻底释放 native 内存（每片后调用）。优先加载 merged 模型保持 KV-cache 一致性。 */
    public void reloadDecoderSession() throws OrtException {
        if (decoderSession != null) {
            try { decoderSession.close(); } catch (Exception e) { /* ignore */ }
            decoderSession = null;
        }
        Path mergedPath = modelDir.resolve("decoder_model_merged.onnx");
        if (Files.exists(mergedPath)) {
            try {
                decoderSession = loadSession(mergedPath);
                log.info("reload: 加载 decoder_model_merged.onnx 成功");
                return;
            } catch (OrtException e) {
                log.warn("reload: decoder_model_merged.onnx 加载失败 ({}), 回退到标准解码器", e.getMessage());
            }
        }
        decoderSession = loadSession(config.decoderModelPath());
        log.info("reload: 使用标准解码器: {}", config.decoderModelPath().getFileName());
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

    private static final int BEAM_SIZE = 5;

    /** 温度回退列表（0.0=贪心，值越大随机性越强）。对齐 faster-whisper 默认。 */
    private static final float[] TEMPERATURES = {0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f};

    /** 贪心解码 decoder 调用预算（maxTargetPositions=448 + 余量）。 */
    private static final long MAX_GREEDY_CALLS = 500;
    /** 温度回退阶段 beam search 总 decoder 调用预算。
     *  防止单分片在重复循环无法被检测到时卡死数小时（4000 次全量前向在 8 核
     *  CPU 上约 100 分钟且完全静默）。800 ≈ 1.7 次满长 beam，最坏 ~20 分钟。 */
    private static final long MAX_BEAM_CALLS = 800;

    /** 解码预算耗尽时抛出，由 {@link #runDecoder} 捕获并返回当前最优结果。 */
    private static final class DecodeBudgetExceeded extends RuntimeException {}

    /**
     * 检测自回归输出是否进入重复循环（Whisper 在低信噪比/静音边界/模糊音频下的常见故障：
     * 反复生成同一短语且不输出 EOT，把生成拖到 maxTargetPositions）。
     * <p>
     * 两级检测：
     * 1. 立即重复——生成尾部最近 pat 个 token 与再往前 pat 个完全相同（pat=2/3/4/5/8）；
     * 2. 短语级循环——尾部 4 元组作为循环块签名，在整个生成序列中出现 ≥3 次
     *    （覆盖较长短语/句子的循环，如 "here for the next variable. So let's..."）。
     * <p>
     * @return 循环起点的绝对下标（应从此处截断生成序列），无循环返回 -1。
     */
    private static int detectLoopStart(int[] tokens, int initLen) {
        int genLen = tokens.length - initLen;
        if (genLen < 8) return -1;
        int end = tokens.length;
        // 1) 立即重复
        for (int pat : new int[]{2, 3, 4, 5, 8}) {
            if (genLen < pat * 2) continue;
            boolean same = true;
            for (int i = 0; i < pat; i++) {
                if (tokens[end - 1 - i] != tokens[end - 1 - pat - i]) {
                    same = false;
                    break;
                }
            }
            if (same) return end - pat * 2;
        }
        // 2) 短语级循环：尾部 4 元组出现 ≥3 次 → 从首次出现处截断
        int p = 4;
        if (genLen >= p * 3) {
            int[] sig = new int[p];
            for (int i = 0; i < p; i++) sig[i] = tokens[end - p + i];
            int firstOcc = -1;
            int count = 0;
            for (int i = initLen; i + p <= end; i++) {
                boolean eq = true;
                for (int j = 0; j < p; j++) {
                    if (tokens[i + j] != sig[j]) { eq = false; break; }
                }
                if (eq) {
                    count++;
                    if (firstOcc < 0) firstOcc = i;
                    if (count >= 3) return firstOcc;
                }
            }
        }
        return -1;
    }

    private int[] runDecoder(float[] encoderState) throws OrtException {
        return runDecoder(encoderState, initialTokens);
    }

    /**
     * 自回归解码：贪心优先 + beam search 回退。
     * <p>
     * 第一轮用贪心解码（temperature=0.0），质量达标则直接返回，避免 beam search 开销。
     * 贪心输出质量差时回退到 beam search + 全温度回退。
     *
     * @param encoderState encoder 输出 states
     * @param initTokens   decoder 初始 token 序列（含或不含 no_timestamps）
     */
    private int[] runDecoder(float[] encoderState, int[] initTokens) throws OrtException {
        // 第一轮：贪心解码（temperature=0.0），~448 次 decoder 调用。
        // 注意：若 initTokens 是带时间戳版本（initialTokensWithTimestamps），
        // 贪心解码用 initialTokens（含 no_timestamps）替代，避免模型输出时间戳 token
        // 主导导致 scoreTranscriptionQuality 跳过所有时间戳返回 quality=0。
        int[] greedyInit = (initTokens == initialTokensWithTimestamps && initialTokens != null)
                ? initialTokens : initTokens;
        int[] greedyTokens;
        // 预算为局部数组，逐分片独立（transcribeChunked 并行线程共享本实例）。
        long[] greedyBudget = {MAX_GREEDY_CALLS};
        if (usePastCache) {
            greedyTokens = runDecoderCache(encoderState, greedyInit, greedyBudget);
        } else {
            greedyTokens = runDecoderFull(encoderState, greedyInit, greedyBudget);
        }

        // 贪心质量检查：质量好直接返回，跳过昂贵的 beam search
        String greedyText = tokenizer.decode(greedyTokens);
        float greedyQuality = scoreTranscriptionQuality(greedyTokens);
        float greedyCompRatio = computeCompressionRatio(greedyText);

        // 诊断：贪心输出了什么
        if (greedyQuality < 0.7f) {
            int tsTokenCount = 0;
            int firstTs = config.noTimestampsTokenId() + 1;
            for (int t : greedyTokens) { if (t >= firstTs) tsTokenCount++; }
            log.warn("贪心诊断: tokens={}, tsTokens={}, text='{}' (len={}), firstTokens={}",
                    greedyTokens.length, tsTokenCount,
                    greedyText.length() > 100 ? greedyText.substring(0, 100) : greedyText,
                    greedyText.length(),
                    greedyTokens.length > 0 ? (greedyTokens[0] + "," +
                        (greedyTokens.length > 1 ? String.valueOf(greedyTokens[1]) : "")) : "empty");
        }

        if (greedyQuality >= 0.7f && greedyCompRatio <= 2.4f) {
            log.info("贪心解码达标: quality={} compRatio={}",
                    String.format("%.2f", greedyQuality), String.format("%.2f", greedyCompRatio));
            return greedyTokens;
        }

        log.info("贪心解码不达标 (quality={} compRatio={}), 回退到 beam search (预算={})",
                String.format("%.2f", greedyQuality), String.format("%.2f", greedyCompRatio),
                MAX_BEAM_CALLS);

        // 第二轮：beam search + 全温度回退。带总 decoder 调用预算，
        // 预算耗尽立即停止（返回当前最优），杜绝单分片卡死数分钟。
        int[] bestTokens = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        long[] beamBudget = {MAX_BEAM_CALLS};

        for (float temp : TEMPERATURES) {
            int[] tokens;
            try {
                log.info("beam search 开始: temp={}, 剩余预算={}", temp, beamBudget[0]);
                tokens = runDecoderBeamSearch(encoderState, BEAM_SIZE, temp, initTokens, beamBudget);
                log.info("beam search 完成: temp={}, 剩余预算={}", temp, beamBudget[0]);
            } catch (DecodeBudgetExceeded e) {
                log.warn("beam search 预算耗尽 (temp={}, 剩余预算={}), 停止温度回退",
                        temp, Math.max(0, beamBudget[0]));
                break;
            }

            float quality = scoreTranscriptionQuality(tokens);
            String text = tokenizer.decode(tokens);
            float compRatio = computeCompressionRatio(text);
            if (compRatio > 2.4f) {
                quality *= 0.3f;
            } else if (compRatio > 1.8f) {
                quality *= 0.6f;
            }

            if (quality > bestScore) {
                bestTokens = tokens;
                bestScore = quality;
                if (quality >= 0.7f && compRatio <= 1.8f) break;
            }
        }

        return bestTokens != null ? bestTokens : greedyTokens;
    }

    /**
     * 转录质量评分 [0,1]：1.0=完美，0.0=完全无意义。
     * 基于非标点 token 占比 + token 多样性（惩罚重复循环）。
     * 用于温度回退时判断是否需要重试。
     */
    private float scoreTranscriptionQuality(int[] tokens) {
        if (tokens == null || tokens.length == 0) return 0f;
        int firstTs = config.noTimestampsTokenId() + 1;
        int meaningful = 0;
        int total = 0;
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (int t : tokens) {
            if (t == eotToken) break;
            if (t >= firstTs) continue; // 跳过时间戳 token（不影响非时间戳模式）
            total++;
            freq.merge(t, 1, Integer::sum);
            String s = tokenizer.tokenToString(t);
            if (s != null && !s.isBlank()) {
                if (!isAllPunctuation(s)) meaningful++;
            }
        }
        if (total == 0) return 0f;

        // 基础质量：有意义 token 占比
        float baseQuality = (float) meaningful / total;

        // token 多样性惩罚：unique token 占比越高越好
        float uniqueRatio = (float) freq.size() / total;
        float diversityPenalty;
        if (uniqueRatio < 0.3f) {
            diversityPenalty = 0.1f;
        } else if (uniqueRatio < 0.5f) {
            diversityPenalty = 0.4f;
        } else if (uniqueRatio < 0.7f) {
            diversityPenalty = 0.8f;
        } else {
            diversityPenalty = 1.0f;
        }

        // 单 token 过度频繁惩罚
        int maxCount = freq.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        float maxRatio = (float) maxCount / total;
        float maxFreqPenalty = maxRatio > 0.2f ? Math.max(0.2f, 1.0f - maxRatio) : 1.0f;

        return baseQuality * diversityPenalty * maxFreqPenalty;
    }

    /**
     * 计算文本的 compression ratio = 原始字节数 / gzip 压缩后字节数。
     * 对齐 faster-whisper 的 compression_ratio 检查。
     * 高压缩比（>2.4）表示输出重复/无意义，应触发温度回退。
     */
    private static float computeCompressionRatio(String text) {
        if (text == null || text.isEmpty()) return 0f;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                gzip.write(bytes);
            }
            int compressedLen = baos.size();
            return compressedLen > 0 ? (float) bytes.length / compressedLen : 0f;
        } catch (IOException e) {
            return 0f;
        }
    }

    // ──────────────────── KV-cache 增量推理 ────────────────────

    private int[] runDecoderCache(float[] encoderState, int[] initTokens, long[] budget) throws OrtException {
        java.util.List<Integer> generated = new java.util.ArrayList<>();
        boolean diag = firstDiag;

        FloatBuffer encBuf = FloatBuffer.wrap(encoderState);
        int srcLen = encoderState.length / config.dModel();
        long[] encShape = {1, srcLen, config.dModel()};

        // frozenResult: 首步 result，其 encoder KV 冻结复用到所有后续步（必须保持打开）。
        // prevResult: 上一步 result，提供每步更新的 decoder KV。
        OrtSession.Result frozenResult = null;
        OrtSession.Result prevResult = null;

        // 重复惩罚滑动窗口
        java.util.ArrayDeque<Integer> recentTokens = new java.util.ArrayDeque<>(repeatWindow);

        try {
            // 首步：use_cache_branch=false 走全量路径（不能 true+空 past，
            // 那样 cross-attn 的 encoder KV 为空，logits 数值错误，实测 diff~5.7）。
            // 全量首步同时产出正确的 encoder KV（冻结复用）与 decoder KV（每步更新）。
            long[] initIds = new long[initTokens.length];
            for (int i = 0; i < initTokens.length; i++) initIds[i] = initTokens[i];

            Map<String, OnnxTensorLike> inputs = new LinkedHashMap<>();
            inputs.put(decoderInputIdsName, OnnxTensor.createTensor(env,
                    LongBuffer.wrap(initIds), new long[]{1, initIds.length}));
            inputs.put(decoderEncoderStateName, OnnxTensor.createTensor(env,
                    encBuf.duplicate(), encShape));

            // 空 past tensors：shape [1, num_heads, 0, head_dim]
            java.util.List<OnnxTensor> emptyPastTensors = new java.util.ArrayList<>();
            if (useCacheBranchName != null) {
                inputs.put(useCacheBranchName, OnnxTensor.createTensor(env, new boolean[]{false}));
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

            if (--budget[0] < 0) throw new DecodeBudgetExceeded();
            prevResult = decoderSession.run(inputs);
            frozenResult = prevResult;
            closeOwnedInputs(inputs);
            for (OnnxTensor t : emptyPastTensors) safeClose(t);

            int firstTs = config.noTimestampsTokenId() + 1;
            int token = getLogitsToken(prevResult, initTokens.length, recentTokens, repeatPenalty, firstTs);
            if (diag) logDiagStep(0, token);
            if (token == eotToken || token < 0) return new int[0];
            generated.add(token);
            addRecentToken(recentTokens, token, repeatWindow);

            // 后续步：只传新 token + past（从 prevResult 的 present 输出获取）。
            // encoder KV 冻结：全部来自 frozenResult（首步全量路径的产物），
            // 不可用 cache 分支输出的 present.*.encoder.*（坏 shape (0,...)，
            // 回填会导致 cross-attn MatMul 广播失败）。decoder KV 每步从 prevResult 更新。
            int maxSteps = Math.min(config.maxTargetPositions() - initTokens.length, 448);
            for (int step = 1; step < maxSteps; step++) {
                long[] ids = new long[]{token};

                Map<String, OnnxTensorLike> nextInputs = new LinkedHashMap<>();
                nextInputs.put(decoderInputIdsName, OnnxTensor.createTensor(env,
                        LongBuffer.wrap(ids), new long[]{1, 1}));
                nextInputs.put(decoderEncoderStateName, OnnxTensor.createTensor(env,
                        encBuf.duplicate(), encShape));
                if (useCacheBranchName != null) {
                    nextInputs.put(useCacheBranchName, OnnxTensor.createTensor(env, new boolean[]{true}));
                }

                // past inputs：全部 past 输入都需提供。
                // encoder.* 从 frozenResult（首步）取，decoder.* 从 prevResult（上一步）取。
                for (String pastName : pastInputNames) {
                    if (pastName.contains(".encoder.")) {
                        nextInputs.put(pastName,
                                (OnnxTensor) frozenResult.get(
                                        pastName.replace("past_key_values", "present")).orElseThrow());
                    } else {
                        nextInputs.put(pastName,
                                (OnnxTensor) prevResult.get(
                                        pastName.replace("past_key_values", "present")).orElseThrow());
                    }
                }

                if (--budget[0] < 0) throw new DecodeBudgetExceeded();
                OrtSession.Result newResult = decoderSession.run(nextInputs);

                if (prevResult != frozenResult) closeResult(prevResult); // 关闭旧 Result
                closeOwnedInputs(nextInputs); // 关闭新创建的 input_ids/enc tensors
                prevResult = newResult;

                int cacheFirstTs = config.noTimestampsTokenId() + 1;
                token = getLogitsToken(prevResult, 1, recentTokens, repeatPenalty, cacheFirstTs);
                if (diag && step < 8) logDiagStep(step, token);
                if (token == eotToken || token < 0) break;
                generated.add(token);
                addRecentToken(recentTokens, token, repeatWindow);
            }

            return generated.stream().mapToInt(Integer::intValue).toArray();
        } finally {
            if (prevResult != frozenResult) closeResult(prevResult);
            closeResult(frozenResult);
        }
    }

    /**
     * 从 Result 的 logits 输出中选取 best token（考虑 suppress 规则 + 重复惩罚）。
     *
     * @param recentTokens 最近生成的 token ID 集合，用于重复惩罚（可为空）
     * @param repeatPenalty 重复惩罚值（>0 时生效，从 logit 中减去 penalty × 出现次数）
     * @param suppressFrom 抑制起始 token ID（如时间戳阈值），低于此值的 token 不会被选
     */
    private int getLogitsToken(OrtSession.Result result, int seqLen,
                               Collection<Integer> recentTokens, float repeatPenalty,
                               int suppressFrom) throws OrtException {
        OnnxTensor logitsTensor = (OnnxTensor) result.get(decoderLogitsName).orElseThrow();
        float[] logits = logitsTensor.getFloatBuffer().array();
        int vocabSize = config.vocabSize();
        int offset = (seqLen - 1) * vocabSize;

        // 构建重复 token 频率表（subtractive 惩罚需要计数）
        java.util.Map<Integer, Integer> repeatFreq = new java.util.HashMap<>();
        if (repeatPenalty > 0 && !recentTokens.isEmpty()) {
            for (int t : recentTokens) {
                repeatFreq.merge(t, 1, Integer::sum);
            }
        }

        int best = 0;
        float bestVal = Float.NEGATIVE_INFINITY;
        // firstStep: 当前输入是否仅含初始 token（无已生成 token）。
        // 需同时检查 initialTokens 和 initialTokensWithTimestamps，
        // 因为 transcribeDetailed 可能使用任意一种。
        boolean firstStep = seqLen == initialTokens.length
                || (initialTokensWithTimestamps != null && seqLen == initialTokensWithTimestamps.length);
        for (int v = 0; v < vocabSize; v++) {
            if (firstStep && config.isBeginSuppressToken(v)) continue;
            if (config.isSuppressToken(v)) continue;
            if (suppressFrom > 0 && v >= suppressFrom) continue;
            float score = logits[offset + v];
            if (repeatPenalty > 0) {
                Integer count = repeatFreq.get(v);
                if (count != null) {
                    score -= repeatPenalty * count;
                }
            }
            if (score > bestVal) {
                bestVal = score;
                best = v;
            }
        }
        return best;
    }

    /** 兼容旧调用（无 suppressFrom=0）。 */
    private int getLogitsToken(OrtSession.Result result, int seqLen,
                               Collection<Integer> recentTokens, float repeatPenalty) throws OrtException {
        return getLogitsToken(result, seqLen, recentTokens, repeatPenalty, 0);
    }

    /** 无重复惩罚版本（兼容旧调用）。 */
    private int getLogitsToken(OrtSession.Result result, int seqLen) throws OrtException {
        return getLogitsToken(result, seqLen, Collections.emptyList(), 1.0f, 0);
    }

    private static void addRecentToken(java.util.ArrayDeque<Integer> deque, int token, int maxSize) {
        if (deque.size() >= maxSize) {
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
    // 默认重复惩罚值（subtractive），>0 生效。
    // 原为 3.0f（当时无时间戳解码 + compression ratio，需强力抑制重复循环）。
    // 现在时间戳解码 + compression ratio 检查已覆盖退化输出检测，降至 1.5f
    // 减少对自然重复词（如 "the the"）的过度抑制。
    // 对齐 faster-whisper 的 divisive 1.1（等效 subtractive ~1.0~1.5）。
    private static final float DEFAULT_REPEAT_PENALTY = 1.5f;
    // 默认重复惩罚窗口大小（最近 N 个 token 计入惩罚）
    private static final int DEFAULT_REPEAT_WINDOW = 20;

    // 实例级别的重复惩罚配置（可通过构造器覆盖默认值）
    private final float repeatPenalty;
    private final int repeatWindow;

    private int[] runDecoderFull(float[] encoderState, int[] initTokens, long[] budget) throws OrtException {
        int[] tokens = initTokens.clone();
        int maxLen = config.maxTargetPositions();
        boolean diag = firstDiag;
        int firstTs = config.noTimestampsTokenId() + 1;

        // 滑动窗口记录最近 token，用于重复惩罚
        java.util.ArrayDeque<Integer> recentTokens = new java.util.ArrayDeque<>(repeatWindow);

        for (int pos = initTokens.length; pos < maxLen; pos++) {
            int lastToken = decodeStepFull(tokens, encoderState, recentTokens, firstTs, budget);
            if (diag && pos < initTokens.length + 8) {
                logDiagStep(pos - initTokens.length, lastToken);
            }
            if (lastToken == eotToken || lastToken < 0) break;
            tokens = Arrays.copyOf(tokens, pos + 1);
            tokens[pos] = lastToken;
            addRecentToken(recentTokens, lastToken, repeatWindow);
            // 重复循环检测：Whisper 在模糊音频上会反复生成同一短语且不输出 EOT，
            // 把生成拖满 maxTargetPositions → 检测到循环时截断到循环起点，
            // 保住前面已生成的有效内容（避免 443-token 退化触发昂贵的 beam search）。
            int loopStart = detectLoopStart(tokens, initTokens.length);
            if (loopStart >= 0) {
                if (diag) {
                    log.info("贪心检测到重复循环 @ step {}, 截断到 token {}", pos - initTokens.length, loopStart);
                }
                tokens = Arrays.copyOf(tokens, loopStart);
                break;
            }
        }

        int start = initTokens.length;
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
                               java.util.ArrayDeque<Integer> recentTokens,
                               int suppressFrom, long[] budget) throws OrtException {
        if (--budget[0] < 0) throw new DecodeBudgetExceeded();
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
                return getLogitsToken(result, seqLen, recentTokens, repeatPenalty, suppressFrom);
            }
        }
    }

    /**
     * 运行 decoder 一步，返回最后一步完整的 logits 向量（不含 offset 裁剪）。
     * 供 {@link #runDecoderBeamSearch} 使用。
     * <p>
     * 支持 merged 模型：当 {@link #usePastCache} 启用时，自动传入
     * {@code use_cache_branch=false} + 空的 past tensors，指示模型走全量计算路径。
     */
    private float[] computeLogits(int[] inputTokens, float[] encoderState, long[] budget) throws OrtException {
        if (--budget[0] < 0) throw new DecodeBudgetExceeded();
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
            // merged 模型需要 use_cache_branch=false + 空 past tensors（全量计算模式）
            java.util.List<OnnxTensor> ownedTensors = new java.util.ArrayList<>();
            if (useCacheBranchName != null) {
                inputs.put(useCacheBranchName, OnnxTensor.createTensor(env, new boolean[]{false}));
                int numHeads = config.decoderAttentionHeads();
                int headDim = config.dModel() / numHeads;
                long[] emptyShape = {1, numHeads, 0, headDim};
                for (String pastName : pastInputNames) {
                    OnnxTensor emptyPast = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape);
                    inputs.put(pastName, emptyPast);
                    ownedTensors.add(emptyPast);
                }
            }
            try (OrtSession.Result result = decoderSession.run(inputs)) {
                OnnxTensor logitsTensor = (OnnxTensor) result.get(decoderLogitsName).orElseThrow();
                float[] fullLogits = logitsTensor.getFloatBuffer().array();
                int vocabSize = config.vocabSize();
                int offset = (seqLen - 1) * vocabSize;
                return Arrays.copyOfRange(fullLogits, offset, offset + vocabSize);
            } finally {
                for (OnnxTensor t : ownedTensors) safeClose(t);
            }
        }
    }

    /**
     * 对 logits 原位应用 subtractive 重复惩罚。
     * 统计 {@code recentTokens} 中每个 token 的出现次数，从对应 logit 中减去 penalty × count。
     * 仅在 penalty > 0 且 recentTokens 非空时起作用。
     */
    private static void applyRepetitionPenalty(float[] logits,
                                                java.util.ArrayDeque<Integer> recentTokens,
                                                float penalty) {
        if (penalty <= 0 || recentTokens == null || recentTokens.isEmpty()) return;
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (int token : recentTokens) {
            freq.merge(token, 1, Integer::sum);
        }
        for (var entry : freq.entrySet()) {
            int token = entry.getKey();
            if (token >= 0 && token < logits.length) {
                logits[token] -= penalty * entry.getValue();
            }
        }
    }

    /**
     * 返回 logits 中概率最高的 topK 个 token ID（已应用 suppress 规则 + 温度缩放）。
     * <p>
     * 注意：重复惩罚应在调用此方法前通过 {@link #applyRepetitionPenalty} 对 logits 原位施加，
     * 本方法不再重复处理。
     *
     * @param temperature 温度参数（0.0=贪心, >0 按 logits/t 排序）
     */
    private int[] topKTokens(float[] logits, int k, boolean firstStep,
                             float temperature) {
        int vocabSize = config.vocabSize();
        java.util.List<ScoredToken> scored = new java.util.ArrayList<>();
        for (int v = 0; v < vocabSize; v++) {
            if (firstStep && config.isBeginSuppressToken(v)) continue;
            if (config.isSuppressToken(v)) continue;
            float score = logits[v];
            if (temperature > 1e-6f) score /= temperature;
            scored.add(new ScoredToken(v, score));
        }
        scored.sort((a, b) -> Float.compare(b.score, a.score));
        int n = Math.min(k, scored.size());
        int[] top = new int[n];
        for (int i = 0; i < n; i++) top[i] = scored.get(i).tokenId;
        return top;
    }

    private record ScoredToken(int tokenId, float score) {}

    /**
     * log-softmax：返回指定 token 的 log 概率（已应用温度缩放）。
     * 使用 log-sum-exp 技巧保证数值稳定性。
     */
    private static float logSoftmax(float[] logits, int token, float temperature) {
        float[] scaled;
        if (temperature > 1e-6f && Math.abs(temperature - 1.0f) > 1e-6f) {
            scaled = new float[logits.length];
            for (int i = 0; i < logits.length; i++) scaled[i] = logits[i] / temperature;
        } else {
            scaled = logits;
        }
        float max = Float.NEGATIVE_INFINITY;
        for (float v : scaled) if (v > max) max = v;
        double sum = 0;
        for (float v : scaled) sum += Math.exp(v - max);
        return (float) (scaled[token] - max - Math.log(sum));
    }

    // ──────────────────── Beam Search 解码 ────────────────────

    /** beam 候选，携带源 beam 索引用于回溯 */
    private record BeamCandidate(int beamIdx, int tokenId, float score) {}

    /** 从 tokens 中截取 initLen 之后的最近 N 个 token 用于重复惩罚。 */
    private static java.util.ArrayDeque<Integer> buildRecentTokens(int[] tokens, int initLen, int window) {
        java.util.ArrayDeque<Integer> recent = new java.util.ArrayDeque<>(window);
        int start = Math.max(initLen, tokens.length - window);
        for (int i = start; i < tokens.length; i++) {
            if (recent.size() >= window) recent.removeFirst();
            recent.addLast(tokens[i]);
        }
        return recent;
    }

    private int[] runDecoderBeamSearch(float[] encoderState, int beamSize, float temperature,
                                       int[] initTokens, long[] budget) throws OrtException {
        if (usePastCache) {
            return runDecoderBeamSearchCached(encoderState, beamSize, temperature, initTokens, budget);
        }
        return runDecoderBeamSearchFull(encoderState, beamSize, temperature, initTokens, budget);
    }

    /**
     * KV-cache 版 beam search：每个 beam 独立维护 decoder KV（增量前向，每步只输入 1 个新 token），
     * encoder KV 冻结共享。相比 {@link #runDecoderBeamSearchFull}（每 beam 每步全量重算整个序列，
     * self-attention O(n^2)），生成阶段每步只需 1 次增量前向，仅首个 beam 步做 1 次全量前向。
     * 仅 merged decoder（usePastCache=true）可用时启用。
     */
    private int[] runDecoderBeamSearchCached(float[] encoderState, int beamSize, float temperature,
                                             int[] initTokens, long[] budget) throws OrtException {
        int maxLen = config.maxTargetPositions();
        int initLen = initTokens.length;
        boolean diag = firstDiag;
        boolean progressLog = !diag;

        FloatBuffer encBuf = FloatBuffer.wrap(encoderState);
        int srcLen = encoderState.length / config.dModel();
        long[] encShape = {1, srcLen, config.dModel()};

        java.util.List<int[]> beamTokens = new java.util.ArrayList<>();
        java.util.List<Float> beamScores = new java.util.ArrayList<>();
        java.util.List<Boolean> beamFinished = new java.util.ArrayList<>();
        java.util.List<OrtSession.Result> beamKvs = new java.util.ArrayList<>();
        java.util.List<OrtSession.Result> opened = new java.util.ArrayList<>();

        OrtSession.Result frozenResult = null;
        try {
            // 首步：所有 beam 初始相同，全量前向一次，冻结 encoder KV 并产出初始 decoder KV
            long[] initIds = new long[initTokens.length];
            for (int i = 0; i < initTokens.length; i++) initIds[i] = initTokens[i];
            Map<String, OnnxTensorLike> inputs = new LinkedHashMap<>();
            inputs.put(decoderInputIdsName, OnnxTensor.createTensor(env,
                    LongBuffer.wrap(initIds), new long[]{1, initIds.length}));
            inputs.put(decoderEncoderStateName, OnnxTensor.createTensor(env, encBuf.duplicate(), encShape));
            java.util.List<OnnxTensor> emptyPastTensors = new java.util.ArrayList<>();
            if (useCacheBranchName != null) {
                inputs.put(useCacheBranchName, OnnxTensor.createTensor(env, new boolean[]{false}));
                int numHeads = config.decoderAttentionHeads();
                int headDim = config.dModel() / numHeads;
                long[] emptyShape = {1, numHeads, 0, headDim};
                for (String pastName : pastInputNames) {
                    OnnxTensor emptyTensor = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape);
                    inputs.put(pastName, emptyTensor);
                    emptyPastTensors.add(emptyTensor);
                }
            }
            if (--budget[0] < 0) throw new DecodeBudgetExceeded();
            frozenResult = decoderSession.run(inputs);
            opened.add(frozenResult);
            closeOwnedInputs(inputs);
            for (OnnxTensor t : emptyPastTensors) safeClose(t);

            float[] firstLogits = extractLastLogits(frozenResult, initLen);
            int[] firstTop = topKTokens(firstLogits, beamSize, true, temperature);
            if (firstTop.length == 0) {
                log.warn("beam search 首步无可用候选（全部被抑制），返回空结果");
                return new int[0];
            }
            for (int token : firstTop) {
                int[] extended = Arrays.copyOf(initTokens, initLen + 1);
                extended[initLen] = token;
                beamTokens.add(extended);
                beamScores.add(logSoftmax(firstLogits, token, temperature));
                beamFinished.add(token == eotToken);
                beamKvs.add(frozenResult);
            }
            if (progressLog) {
                int active = 0;
                for (boolean f : beamFinished) if (!f) active++;
                log.info("beam search 进度: temp={}, step={}, 活跃beam={}, 剩余预算={}",
                        temperature, 0, active, Math.max(0, budget[0]));
            }
            if (beamFinished.stream().allMatch(f -> f)) {
                return selectBestBeam(beamTokens, beamScores, beamFinished, initLen);
            }

            for (int pos = initLen + 1; pos < maxLen; pos++) {
                java.util.List<BeamCandidate> candidates = new java.util.ArrayList<>();
                java.util.Map<Integer, OrtSession.Result> produced = new java.util.HashMap<>();

                for (int b = 0; b < beamTokens.size(); b++) {
                    if (beamFinished.get(b)) continue;
                    int[] seq = beamTokens.get(b);
                    long lastToken = seq[seq.length - 1];

                    // 增量前向：1 个新 token + 该 beam 的 decoder KV + 冻结 encoder KV
                    Map<String, OnnxTensorLike> nextInputs = new LinkedHashMap<>();
                    nextInputs.put(decoderInputIdsName, OnnxTensor.createTensor(env,
                            LongBuffer.wrap(new long[]{lastToken}), new long[]{1, 1}));
                    nextInputs.put(decoderEncoderStateName, OnnxTensor.createTensor(env,
                            encBuf.duplicate(), encShape));
                    if (useCacheBranchName != null) {
                        nextInputs.put(useCacheBranchName, OnnxTensor.createTensor(env, new boolean[]{true}));
                    }
                    for (String pastName : pastInputNames) {
                        if (pastName.contains(".encoder.")) {
                            nextInputs.put(pastName, (OnnxTensor) frozenResult.get(
                                    pastName.replace("past_key_values", "present")).orElseThrow());
                        } else {
                            nextInputs.put(pastName, (OnnxTensor) beamKvs.get(b).get(
                                    pastName.replace("past_key_values", "present")).orElseThrow());
                        }
                    }
                    if (--budget[0] < 0) throw new DecodeBudgetExceeded();
                    OrtSession.Result newResult = decoderSession.run(nextInputs);
                    opened.add(newResult);
                    produced.put(b, newResult);
                    closeOwnedInputs(nextInputs);

                    float[] logits = extractLastLogits(newResult, 1);
                    java.util.ArrayDeque<Integer> beamRecent = buildRecentTokens(seq, initLen, repeatWindow);
                    if (repeatPenalty > 0 && !beamRecent.isEmpty()) {
                        applyRepetitionPenalty(logits, beamRecent, repeatPenalty);
                    }
                    int[] top = topKTokens(logits, beamSize, false, temperature);
                    for (int token : top) {
                        float logProb = logSoftmax(logits, token, temperature);
                        candidates.add(new BeamCandidate(b, token, beamScores.get(b) + logProb));
                    }
                }

                if (candidates.isEmpty()) break;
                candidates.sort((a, b) -> Float.compare(b.score, a.score));

                int keep = Math.min(beamSize, candidates.size());
                java.util.List<int[]> nextTokens = new java.util.ArrayList<>();
                java.util.List<Float> nextScores = new java.util.ArrayList<>();
                java.util.List<Boolean> nextFinished = new java.util.ArrayList<>();
                java.util.List<OrtSession.Result> nextKvs = new java.util.ArrayList<>();
                java.util.Set<OrtSession.Result> retained =
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (int ci = 0; ci < keep; ci++) {
                    BeamCandidate c = candidates.get(ci);
                    int[] src = beamTokens.get(c.beamIdx);
                    int[] extended = Arrays.copyOf(src, src.length + 1);
                    extended[src.length] = c.tokenId;
                    nextTokens.add(extended);
                    nextScores.add(c.score);
                    nextFinished.add(c.tokenId == eotToken);
                    // 本轮前向过的 beam 用新 KV；未前向的（上轮已 finished）沿用旧 KV
                    OrtSession.Result kv = produced.get(c.beamIdx);
                    if (kv == null) kv = beamKvs.get(c.beamIdx);
                    nextKvs.add(kv);
                    retained.add(kv);
                }

                // 关闭未被保留候选引用的本轮产物与上轮 KV（frozenResult 保留到最后）。
                // 同一 Result 可能被多个 beam 共享引用（beamKvs/nextKvs 中重复出现），
                // 必须先去重再关闭，否则同一 Result 被 close 多次触发 ORT 的
                // "Closing an already closed Result" 警告。
                java.util.Set<OrtSession.Result> toClose =
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (OrtSession.Result r : produced.values()) {
                    if (!retained.contains(r)) toClose.add(r);
                }
                for (OrtSession.Result r : beamKvs) {
                    if (r != frozenResult && !retained.contains(r)) toClose.add(r);
                }
                for (OrtSession.Result r : toClose) {
                    safeClose(r);
                    opened.remove(r);
                }

                beamTokens = nextTokens;
                beamScores = nextScores;
                beamFinished = nextFinished;
                beamKvs = nextKvs;

                int bestB = 0;
                float bestBScore = Float.NEGATIVE_INFINITY;
                for (int b = 0; b < beamTokens.size(); b++) {
                    if (!beamFinished.get(b) && beamScores.get(b) > bestBScore) {
                        bestBScore = beamScores.get(b);
                        bestB = b;
                    }
                }
                if (detectLoopStart(beamTokens.get(bestB), initLen) >= 0) {
                    if (diag) {
                        log.info("beam 检测到重复循环, 提前终止 @ step {}", pos - initLen);
                    }
                    break;
                }

                if (progressLog && (pos - initLen) % 20 == 0) {
                    int activeBeams = 0;
                    for (boolean f : beamFinished) if (!f) activeBeams++;
                    log.info("beam search 进度: temp={}, step={}, 活跃beam={}, 剩余预算={}",
                            temperature, pos - initLen, activeBeams,
                            Math.max(0, budget[0]));
                }

                if (beamFinished.stream().allMatch(f -> f)) break;
            }

            if (diag) {
                firstDiag = false;
                for (int b = 0; b < beamTokens.size(); b++) {
                    int[] t = beamTokens.get(b);
                    int eotAt = -1;
                    for (int i = initLen; i < t.length; i++) if (t[i] == eotToken) { eotAt = i; break; }
                    String text = tokenizer.decode(eotAt >= 0
                            ? java.util.Arrays.copyOfRange(t, initLen, eotAt)
                            : java.util.Arrays.copyOfRange(t, initLen, t.length));
                    log.info("诊断 beam[{}] final: score={} text=\"{}\"", b,
                            String.format("%.2f", beamScores.get(b)),
                            text.length() > 60 ? text.substring(0, 60) + "..." : text);
                }
            }

            return selectBestBeam(beamTokens, beamScores, beamFinished, initLen);
        } finally {
            for (OrtSession.Result r : opened) {
                if (r != null) safeClose(r);
            }
        }
    }

    /**
     * 从 KV-cache 前向结果中提取最后一个位置（seqLen-1）的 logits 向量。
     * seqLen=1 时即该增量步的预测分布。
     */
    private float[] extractLastLogits(OrtSession.Result result, int seqLen) throws OrtException {
        OnnxTensor logitsTensor = (OnnxTensor) result.get(decoderLogitsName).orElseThrow();
        float[] fullLogits = logitsTensor.getFloatBuffer().array();
        int vocabSize = config.vocabSize();
        int offset = (seqLen - 1) * vocabSize;
        return Arrays.copyOfRange(fullLogits, offset, offset + vocabSize);
    }

    /** 选择最优 beam：优先已结束（命中 EOT）的 beam，否则取长度归一化分数最高者。 */
    private int[] selectBestBeam(java.util.List<int[]> beamTokens, java.util.List<Float> beamScores,
                                 java.util.List<Boolean> beamFinished, int initLen) {
        double lengthPenalty = 0.5;
        int bestIdx = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < beamTokens.size(); i++) {
            if (!beamFinished.get(i)) continue;
            int genLen = beamTokens.get(i).length - initLen;
            float normScore = beamScores.get(i) / (float) Math.pow(Math.max(genLen, 1), lengthPenalty);
            if (normScore > bestScore) {
                bestScore = normScore;
                bestIdx = i;
            }
        }
        if (bestScore == Float.NEGATIVE_INFINITY) {
            for (int i = 0; i < beamTokens.size(); i++) {
                int genLen = beamTokens.get(i).length - initLen;
                float normScore = beamScores.get(i) / (float) Math.pow(Math.max(genLen, 1), lengthPenalty);
                if (normScore > bestScore) {
                    bestScore = normScore;
                    bestIdx = i;
                }
            }
        }
        int[] tokens = beamTokens.get(bestIdx);
        int start = initLen;
        int end = tokens.length;
        for (int i = start; i < tokens.length; i++) {
            if (tokens[i] == eotToken) { end = i; break; }
        }
        return java.util.Arrays.copyOfRange(tokens, start, end);
    }

    private int[] runDecoderBeamSearchFull(float[] encoderState, int beamSize, float temperature,
                                           int[] initTokens, long[] budget) throws OrtException {
        int maxLen = config.maxTargetPositions();
        int initLen = initTokens.length;
        boolean diag = firstDiag;
        // 回退 beam search 的周期进度日志：非首分片（diag=false）也打印，
        // 避免"回退后数十分钟静默无输出"被误判为假死。
        boolean progressLog = !diag;

        // beam 状态：tokens、累积分数、是否已结束
        java.util.List<int[]> beamTokens = new java.util.ArrayList<>();
        java.util.List<Float> beamScores = new java.util.ArrayList<>();
        java.util.List<Boolean> beamFinished = new java.util.ArrayList<>();
        beamTokens.add(initTokens.clone());
        beamScores.add(0f);
        beamFinished.add(false);

        for (int pos = initLen; pos < maxLen; pos++) {
            java.util.List<BeamCandidate> candidates = new java.util.ArrayList<>();

            for (int b = 0; b < beamTokens.size(); b++) {
                if (beamFinished.get(b)) continue;

                float[] logits = computeLogits(beamTokens.get(b), encoderState, budget);
                boolean firstStep = (pos == initLen);
                java.util.ArrayDeque<Integer> beamRecent = buildRecentTokens(beamTokens.get(b), initLen, repeatWindow);

                // 原位施加 subtractive 重复惩罚（同时影响候选选择和 beam score）
                if (repeatPenalty > 0 && !beamRecent.isEmpty()) {
                    applyRepetitionPenalty(logits, beamRecent, repeatPenalty);
                }

                int[] top = topKTokens(logits, beamSize, firstStep, temperature);

                for (int token : top) {
                    float logProb = logSoftmax(logits, token, temperature);
                    candidates.add(new BeamCandidate(b, token, beamScores.get(b) + logProb));
                }
            }

            if (candidates.isEmpty()) break;

            // 全局排序 + 剪枝到 beamSize
            candidates.sort((a, b) -> Float.compare(b.score, a.score));

            java.util.List<int[]> nextTokens = new java.util.ArrayList<>();
            java.util.List<Float> nextScores = new java.util.ArrayList<>();
            java.util.List<Boolean> nextFinished = new java.util.ArrayList<>();

            int keep = Math.min(beamSize, candidates.size());
            for (int ci = 0; ci < keep; ci++) {
                BeamCandidate c = candidates.get(ci);
                int[] src = beamTokens.get(c.beamIdx);
                int[] extended = Arrays.copyOf(src, src.length + 1);
                extended[src.length] = c.tokenId;
                nextTokens.add(extended);
                nextScores.add(c.score);
                nextFinished.add(c.tokenId == eotToken);
            }

            beamTokens = nextTokens;
            beamScores = nextScores;
            beamFinished = nextFinished;

            // 重复循环检测：顶部 beam 进入循环（不输出 EOT）时提前终止，
            // 避免 beam 拖满 maxTargetPositions 造成单分片数分钟卡死。
            int bestB = 0;
            float bestBScore = Float.NEGATIVE_INFINITY;
            for (int b = 0; b < beamTokens.size(); b++) {
                if (!beamFinished.get(b) && beamScores.get(b) > bestBScore) {
                    bestBScore = beamScores.get(b);
                    bestB = b;
                }
            }
            if (detectLoopStart(beamTokens.get(bestB), initLen) >= 0) {
                if (diag) {
                    log.info("beam 检测到重复循环, 提前终止 @ step {}", pos - initLen);
                }
                break;
            }

            // 诊断日志（首 chunk 前几步）
            if (diag && pos < initLen + 8) {
                for (int b = 0; b < Math.min(3, beamTokens.size()); b++) {
                    int last = beamTokens.get(b)[beamTokens.get(b).length - 1];
                    log.info("诊断 beam[{}] step {}: token={} score={}",
                            b, pos - initLen, last, String.format("%.2f", beamScores.get(b)));
                }
            }

            // 回退 beam 周期进度：每 20 步报一次，证明任务仍在推进
            if (progressLog && (pos - initLen) % 20 == 0) {
                int activeBeams = 0;
                for (boolean f : beamFinished) if (!f) activeBeams++;
                log.info("beam search 进度: temp={}, step={}, 活跃beam={}, 剩余预算={}",
                        temperature, pos - initLen, activeBeams,
                        Math.max(0, budget[0]));
            }

            if (beamFinished.stream().allMatch(f -> f)) break;
        }

        if (diag) {
            firstDiag = false;
            for (int b = 0; b < beamTokens.size(); b++) {
                int[] t = beamTokens.get(b);
                int eotAt = -1;
                for (int i = initLen; i < t.length; i++) if (t[i] == eotToken) { eotAt = i; break; }
                String text = tokenizer.decode(eotAt >= 0
                        ? java.util.Arrays.copyOfRange(t, initLen, eotAt)
                        : java.util.Arrays.copyOfRange(t, initLen, t.length));
                log.info("诊断 beam[{}] final: score={} text=\"{}\"", b,
                        String.format("%.2f", beamScores.get(b)),
                        text.length() > 60 ? text.substring(0, 60) + "..." : text);
            }
        }

        // 选最优 beam：优先选已结束的，否则选分数最高的
        // 使用长度归一化防止偏向短序列（对齐 faster-whisper）
        double lengthPenalty = 0.5;
        int bestIdx = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < beamTokens.size(); i++) {
            if (!beamFinished.get(i)) continue;
            int genLen = beamTokens.get(i).length - initLen;
            float normScore = beamScores.get(i) / (float) Math.pow(Math.max(genLen, 1), lengthPenalty);
            if (normScore > bestScore) {
                bestScore = normScore;
                bestIdx = i;
            }
        }
        if (bestScore == Float.NEGATIVE_INFINITY) {
            for (int i = 0; i < beamTokens.size(); i++) {
                int genLen = beamTokens.get(i).length - initLen;
                float normScore = beamScores.get(i) / (float) Math.pow(Math.max(genLen, 1), lengthPenalty);
                if (normScore > bestScore) {
                    bestScore = normScore;
                    bestIdx = i;
                }
            }
        }

        int[] tokens = beamTokens.get(bestIdx);
        int start = initLen;
        int end = tokens.length;
        for (int i = start; i < tokens.length; i++) {
            if (tokens[i] == eotToken) { end = i; break; }
        }
        return java.util.Arrays.copyOfRange(tokens, start, end);
    }

    // ──────────────────── VAD 滤波 / 静音分片 ────────────────────
    // 方法已提取至 WhisperVad.java

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
        opts.setIntraOpNumThreads(defaultNumThreads());
        opts.setCPUArenaAllocator(false);
        opts.setMemoryPatternOptimization(false);
        return OrtEnvironment.getEnvironment().createSession(modelPath.toString(), opts);
    }

    /**
     * CPU 推理线程数：优先读 ONNX_NUM_THREADS 环境变量，缺省用全部可用核。
     * 与 MDX-NET 分离器（MdxNetModel.defaultNumThreads）读取同一变量；
     * 此前硬编码 2 线程导致 8 核机器只用到 1/4 算力。
     */
    private static int defaultNumThreads() {
        String env = System.getenv("ONNX_NUM_THREADS");
        if (env != null && !env.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(env.trim()));
            } catch (NumberFormatException e) {
                log.warn("ONNX_NUM_THREADS 格式无效: {}, 使用默认值", env);
            }
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
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
