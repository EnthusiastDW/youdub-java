package site.dengwei.onnxruntime.whisper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WhisperConfig {

    private static final Logger log = LoggerFactory.getLogger(WhisperConfig.class);

    private final int numMelBins;
    private final int dModel;
    private final int encoderLayers;
    private final int decoderLayers;
    private final int encoderAttentionHeads;
    private final int decoderAttentionHeads;
    private final int encoderFFNDimension;
    private final int decoderFFNDimension;
    private final int maxSourcePositions;
    private final int maxTargetPositions;
    private final int vocabSize;
    private final int bosTokenId;
    private final int eosTokenId;
    private final int decoderStartTokenId;

    // forced_decoder_ids from config: positions → fixed token IDs
    // e.g. .en: [[1, 50362]]; multilingual: [[1, 50259], [2, 50359]]
    private final int[][] forcedDecoderIds;

    // Derived special token IDs (NOT from vocab.json — they're in the model's embedding table)
    private final int transcribeTokenId;
    private final int noTimestampsTokenId;

    // Suppression lists from config.json
    private final int[] beginSuppressTokens;
    private final int[] suppressTokens;

    private final Path encoderModelPath;
    private final Path decoderModelPath;
    private final Path decoderMergedPath;
    private final Path vocabPath;
    private final Path mergesPath;

    public WhisperConfig(Path modelDir) {
        if (!Files.isDirectory(modelDir)) {
            throw new IllegalArgumentException("模型目录不存在: " + modelDir);
        }

        Path configFile = modelDir.resolve("config.json");
        JSONObject json;
        try {
            String content = Files.readString(configFile);
            json = new JSONObject(content);
        } catch (IOException e) {
            throw new RuntimeException("读取 config.json 失败: " + configFile, e);
        }

        this.numMelBins            = json.optInt("num_mel_bins", 80);
        this.dModel                = json.optInt("d_model", 384);
        this.encoderLayers         = json.optInt("encoder_layers", 4);
        this.decoderLayers         = json.optInt("decoder_layers", 4);
        this.encoderAttentionHeads = json.optInt("encoder_attention_heads", 6);
        this.decoderAttentionHeads = json.optInt("decoder_attention_heads", 6);
        this.encoderFFNDimension   = json.optInt("encoder_ffn_dim", 1536);
        this.decoderFFNDimension   = json.optInt("decoder_ffn_dim", 1536);
        this.maxSourcePositions    = json.optInt("max_source_positions", 1500);
        this.maxTargetPositions    = json.optInt("max_target_positions", 448);
        this.vocabSize             = json.optInt("vocab_size", 51865);
        this.bosTokenId            = json.optInt("bos_token_id", 50257);
        this.eosTokenId            = json.optInt("eos_token_id", 50256);
        this.decoderStartTokenId   = json.optInt("decoder_start_token_id", 50257);

        // Parse forced_decoder_ids: [[pos, tokenId], ...]
        JSONArray raw = json.optJSONArray("forced_decoder_ids");
        if (raw != null) {
            this.forcedDecoderIds = new int[raw.length()][2];
            for (int i = 0; i < raw.length(); i++) {
                JSONArray pair = raw.getJSONArray(i);
                forcedDecoderIds[i][0] = pair.getInt(0);
                forcedDecoderIds[i][1] = pair.getInt(1);
            }
        } else {
            this.forcedDecoderIds = new int[0][2];
        }

        // Derive special token IDs (they exist in model embedding, NOT in vocab.json)
        // For .en: forcedDecoderIds = [[1, 50362]] → transcribe=50362, notimestamps=50363（+1）
        // For multilingual: forcedDecoderIds = [[1, LANG], [2, TASK]] → transcribe=50359,
        //   translate=50360, notimestamps=50361（+2）。多语言模型在 transcribe 与
        //   notimestamps 之间还有 translate token，偏移量不同。
        if (forcedDecoderIds.length > 0) {
            this.transcribeTokenId = forcedDecoderIds[forcedDecoderIds.length - 1][1];
            this.noTimestampsTokenId = forcedDecoderIds.length >= 2
                    ? transcribeTokenId + 2 : transcribeTokenId + 1;
        } else {
            this.transcribeTokenId = decoderStartTokenId + 2;
            this.noTimestampsTokenId = decoderStartTokenId + 3;
        }

        // Parse suppress tokens
        this.beginSuppressTokens = parseIdArray(json.optJSONArray("begin_suppress_tokens"));
        this.suppressTokens = parseIdArray(json.optJSONArray("suppress_tokens"));

        this.encoderModelPath = modelDir.resolve("encoder_model.onnx");
        // fp16 decoder 优先（内存减半），回退 fp32。
        // decoder_model_merged.onnx（含 KV-cache）在 ORT 1.22+ 下可用（1.20 会挂起，
        // 现已升级）。merged 由 WhisperModel 按需加载；标准 decoder 仍作为回退。
        Path fp16Path = modelDir.resolve("decoder_model_fp16.onnx");
        this.decoderModelPath = Files.exists(fp16Path) ? fp16Path
                : modelDir.resolve("decoder_model.onnx");
        this.decoderMergedPath = modelDir.resolve("decoder_model_merged.onnx");
        this.vocabPath        = modelDir.resolve("vocab.json");
        this.mergesPath       = modelDir.resolve("merges.txt");

        if (!Files.exists(encoderModelPath)) {
            throw new IllegalArgumentException("encoder ONNX 不存在: " + encoderModelPath);
        }
        if (!Files.exists(decoderModelPath)) {
            throw new IllegalArgumentException("decoder ONNX 不存在: " + decoderModelPath);
        }

        log.info("Whisper 模型配置加载: {}  encoder_layers={} decoder_layers={} d_model={} vocab={}",
                modelDir, encoderLayers, decoderLayers, dModel, vocabSize);
    }

    // ─── Getters ───

    public int numMelBins()               { return numMelBins; }
    public int dModel()                   { return dModel; }
    public int encoderLayers()            { return encoderLayers; }
    public int decoderLayers()            { return decoderLayers; }
    public int encoderAttentionHeads()    { return encoderAttentionHeads; }
    public int decoderAttentionHeads()    { return decoderAttentionHeads; }
    public int encoderFFNDimension()      { return encoderFFNDimension; }
    public int decoderFFNDimension()      { return decoderFFNDimension; }
    public int maxSourcePositions()       { return maxSourcePositions; }
    public int maxTargetPositions()       { return maxTargetPositions; }
    public int vocabSize()                { return vocabSize; }
    public int bosTokenId()               { return bosTokenId; }
    public int eosTokenId()               { return eosTokenId; }
    public int decoderStartTokenId()      { return decoderStartTokenId; }
    public int transcribeTokenId()        { return transcribeTokenId; }
    public int noTimestampsTokenId()      { return noTimestampsTokenId; }
    public int[][] forcedDecoderIds()     { return forcedDecoderIds; }
    public int[] beginSuppressTokens()    { return beginSuppressTokens; }
    public int[] suppressTokens()         { return suppressTokens; }
    public boolean isBeginSuppressToken(int id) {
        for (int t : beginSuppressTokens) if (t == id) return true;
        return false;
    }
    public boolean isSuppressToken(int id) {
        for (int t : suppressTokens) if (t == id) return true;
        return false;
    }

    /**
     * 对多语言模型，将 forced_decoder_ids 中的语言 token 替换为目标语言。
     * <p>
     * 多语言模型 forced_decoder_ids 形如 {@code [[1, LANG], [2, TASK]]}（2 对），
     * 本方法把位置 1 的 LANG 替换为 {@code langTokenId}。
     * 英文专用模型（.en）只有 1 对，无法切换语言，会告警并跳过。
     *
     * @param langTokenId 目标语言的 token ID（如 {@code<|zh|>} 对应的 ID）
     */
    public void overrideLanguage(int langTokenId) {
        if (forcedDecoderIds.length < 2) {
            log.warn("当前模型 forced_decoder_ids 只有 {} 对，疑似英文专用模型(.en)，无法切换语言（langTokenId={} 已忽略）",
                    forcedDecoderIds.length, langTokenId);
            return;
        }
        int old = forcedDecoderIds[0][1];
        forcedDecoderIds[0][1] = langTokenId;
        log.info("Whisper 语言切换：forced_decoder_ids[0][1] {} -> {}", old, langTokenId);
    }

    /**
     * Returns the initial decoder token sequence for the model type.
     * For .en: [SOT, task_token, no_timestamps]
     * For multilingual: [SOT, lang_token, task_token, no_timestamps]
     */
    public int[] initialDecoderTokens() {
        int[] tokens = new int[forcedDecoderIds.length + 2];
        tokens[0] = decoderStartTokenId; // SOT
        for (int i = 0; i < forcedDecoderIds.length; i++) {
            tokens[i + 1] = forcedDecoderIds[i][1];
        }
        tokens[tokens.length - 1] = noTimestampsTokenId;
        return tokens;
    }

    /** Decoder start tokens WITHOUT the no_timestamps token, so the model
     *  will naturally output timestamp tokens during generation. */
    public int[] initialDecoderTokensWithTimestamps() {
        int[] tokens = new int[forcedDecoderIds.length + 1];
        tokens[0] = decoderStartTokenId; // SOT
        for (int i = 0; i < forcedDecoderIds.length; i++) {
            tokens[i + 1] = forcedDecoderIds[i][1];
        }
        return tokens;
    }

    public Path encoderModelPath()        { return encoderModelPath; }
    public Path decoderModelPath()        { return decoderModelPath; }
    public Path decoderMergedPath()       { return decoderMergedPath; }
    public Path vocabPath()               { return vocabPath; }
    public Path mergesPath()              { return mergesPath; }

    private static int[] parseIdArray(JSONArray arr) {
        if (arr == null) return new int[0];
        int[] result = new int[arr.length()];
        for (int i = 0; i < arr.length(); i++) result[i] = arr.getInt(i);
        return result;
    }
}
