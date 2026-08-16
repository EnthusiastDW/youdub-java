package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;

import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandResult;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import static com.youdub.replica.service.adapter.AdapterConstants.WHISPER_CPP;

/**
 * whisper.cpp 语音识别适配器（whisper-cli 子进程）。
 * <p>
 * 流程：FFmpeg 统一转 16kHz mono WAV → 长音频按 {@code chunkMinutes} 分片 →
 * 逐片调用 {@code whisper-cli -ojf}（full JSON 含 token 级时间戳）→ 合并为
 * 标准 ASR JSON（词级时间戳对齐 {@link UtteranceProcessor} 的重分段逻辑）。
 * <p>
 * 模型（large-v3-turbo Q5_0 + Silero-VAD）首次调用时懒下载到
 * {@code data/whisper-models/}（Docker 挂载卷）。
 */
@Slf4j
@Component(WHISPER_CPP)
@RequiredArgsConstructor
public class WhisperCppRecognizer implements SpeechRecognizer {

    /** 单条 utterance 最大时长，超出则强制断句（字幕可读性，对齐 OnnxWhisperRecognizer） */
    private static final long MAX_UTT_DUR_MS = 7000;
    /** 单条 utterance 最大字符数，超出则强制断句 */
    private static final int MAX_UTT_CHARS = 100;
    /** 句内停顿超过该阈值视为自然断句点 */
    private static final long GAP_SPLIT_MS = 500;
    /** 强制断句时，句内间隔达到该阈值才视为"自然断点" */
    private static final long MIN_INTERNAL_GAP_MS = 150;

    /** whisper-cli 输出 JSON 文件后缀（-ojf 输出 {outputBase}.json） */
    private static final String JSON_SUFFIX = ".json";

    /** 默认模型目录（相对工作目录；Docker 挂载 /app/data/whisper-models） */
    private static final String DEFAULT_MODEL_DIR = "data/whisper-models";

    /**
     * 并发转录上限。whisper-cli 每次加载 ~574MB 模型，多任务并发会叠加内存。
     * 信号量在 Spring 层强制串行，与 PipelineOrchestrator 的 stageGates 互为兜底。
     * 通过环境变量 {@code WHISPER_CPP_MAX_CONCURRENT} 配置，默认 1。
     */
    private final Semaphore transcribeGate = new Semaphore(maxConcurrentTranscribes());

    private static int maxConcurrentTranscribes() {
        String env = System.getenv("WHISPER_CPP_MAX_CONCURRENT");
        if (env != null && !env.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(env.trim()));
            } catch (NumberFormatException e) {
                // 非法值回退默认
            }
        }
        return 1;
    }

    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Override
    public void transcribe(Task task, Path audioPath, Path outputDir, String language) throws Exception {
        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }
        Files.createDirectories(outputDir);

        Path asrFile = outputDir.resolve("asr.json");
        if (Files.exists(asrFile)) {
            log.info("ASR 结果已存在，跳过：{}", asrFile);
            return;
        }

        try {
            transcribeGate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 whisper-cpp 并发许可被中断", e);
        }
        try {
            transcribeInternal(task, audioPath, outputDir, language);
        } finally {
            transcribeGate.release();
        }
    }

    private void transcribeInternal(Task task, Path audioPath, Path outputDir, String language) throws Exception {
        Path asrFile = outputDir.resolve("asr.json");
        AppProperties.Asr.WhisperCpp cfg = settingsService.getProviderConfig(WHISPER_CPP, AppProperties.Asr.WhisperCpp.class);
        Path modelDir = resolveModelDir(cfg);
        Path modelPath = resolveModelFile(cfg, modelDir);
        Path vadModelPath = cfg.isVad() ? resolveVadModelFile(cfg, modelDir) : null;

        log.info("whisper.cpp 识别开始：task={}, audio={}, lang={}, model={}, vad={}",
                task.getId(), audioPath, language, modelPath.getFileName(), vadModelPath != null);

        long t0 = System.currentTimeMillis();

        List<Path> chunkWavs = new ArrayList<>();
        List<long[]> chunkRanges = new ArrayList<>();
        try {
            // FFmpeg 转 16kHz mono WAV，并按需分片
            prepareChunks(task, audioPath, outputDir, cfg, chunkWavs, chunkRanges);

            // 逐片调用 whisper-cli -ojf，收集词级时间戳
            List<ObjectNode> segments = new ArrayList<>();
            long chunkStartMs = 0;
            for (int i = 0; i < chunkWavs.size(); i++) {
                Path chunk = chunkWavs.get(i);
                List<ObjectNode> chunkSegs = runWhisperCli(task, chunk, outputDir, language, cfg, modelPath, vadModelPath);
                for (ObjectNode seg : chunkSegs) {
                    shiftSegmentOffsets(seg, chunkStartMs);
                    segments.add(seg);
                }
                chunkStartMs += chunkRanges.get(i)[1];
            }

            long elapsed = System.currentTimeMillis() - t0;
            log.info("whisper.cpp 识别完成：task={}, chunks={}, segments={}, elapsed={}ms",
                    task.getId(), chunkWavs.size(), segments.size(), elapsed);

            ObjectNode json = buildAsrJson(segments, audioPath);
            Files.writeString(asrFile, objectMapper.writeValueAsString(json));
            log.info("ASR 结果已保存：{}", asrFile);
        } finally {
            for (Path chunk : chunkWavs) {
                try {
                    Files.deleteIfExists(chunk);
                } catch (IOException e) {
                    log.warn("删除临时分片失败：{}", chunk);
                }
            }
        }
    }

    // ────────────────────────────── 模型解析 ──────────────────────────────

    private Path resolveModelDir(AppProperties.Asr.WhisperCpp cfg) {
        String modelDir = cfg.getModelDir();
        if (modelDir == null || modelDir.isBlank()) {
            modelDir = DEFAULT_MODEL_DIR;
        }
        return Paths.get(modelDir).toAbsolutePath();
    }

    private Path resolveModelFile(AppProperties.Asr.WhisperCpp cfg, Path modelDir) throws IOException {
        if (cfg.getModelPath() != null && !cfg.getModelPath().isBlank()) {
            return Paths.get(cfg.getModelPath()).toAbsolutePath();
        }
        String modelName = cfg.getModel() != null && !cfg.getModel().isBlank()
                ? cfg.getModel()
                : WhisperCppModels.WHISPER_MODEL;
        return WhisperCppModels.ensureWhisperModel(modelDir, modelName);
    }

    private Path resolveVadModelFile(AppProperties.Asr.WhisperCpp cfg, Path modelDir) throws IOException {
        if (cfg.getVadModelPath() != null && !cfg.getVadModelPath().isBlank()) {
            return Paths.get(cfg.getVadModelPath()).toAbsolutePath();
        }
        String vadModelName = cfg.getVadModel() != null && !cfg.getVadModel().isBlank()
                ? cfg.getVadModel()
                : WhisperCppModels.VAD_MODEL;
        return WhisperCppModels.ensureVadModel(modelDir, vadModelName);
    }

    // ────────────────────────────── FFmpeg 预处理与分片 ──────────────────────────────

    /**
     * 用 FFprobe 探测音频时长（秒）。
     */
    private double probeDuration(Path audioPath) throws Exception {
        CommandResult r = CommandRunner.run(Command.builder()
                .add("ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1", audioPath.toString())
                .timeout(30_000)
                .build());
        String out = r.output().trim();
        if (out.isBlank()) {
            throw new RuntimeException("ffprobe 无法获取音频时长：" + audioPath);
        }
        return Double.parseDouble(out);
    }

    /**
     * 将输入音频转为 16kHz mono WAV；若时长超过 chunkMinutes 则按固定窗口分片。
     * 每片的起止秒数记录到 {@code chunkRanges}（[startSec, durSec]）。
     */
    private void prepareChunks(Task task, Path audioPath, Path outputDir, AppProperties.Asr.WhisperCpp cfg,
                               List<Path> chunkWavs, List<long[]> chunkRanges) throws Exception {
        double durationSec = probeDuration(audioPath);
        int chunkSec = Math.max(1, cfg.getChunkMinutes() * 60);

        if (durationSec <= chunkSec) {
            Path wav = outputDir.resolve("asr_16k.wav");
            toMono16kWav(audioPath, wav, 0, (long) durationSec, cfg);
            chunkWavs.add(wav);
            chunkRanges.add(new long[]{0, (long) Math.ceil(durationSec) * 1000});
            log.info("whisper.cpp 音频时长 {}s ≤ {}min，单次识别", Math.round(durationSec), cfg.getChunkMinutes());
            return;
        }

        int nChunks = (int) Math.ceil(durationSec / chunkSec);
        log.info("whisper.cpp 音频时长 {}s > {}min，分 {} 片识别", Math.round(durationSec), cfg.getChunkMinutes(), nChunks);
        for (int i = 0; i < nChunks; i++) {
            long startSec = (long) (i * chunkSec);
            long durSec = (long) Math.min(chunkSec, Math.ceil(durationSec - startSec));
            Path wav = outputDir.resolve("asr_chunk_" + i + ".wav");
            toMono16kWav(audioPath, wav, startSec, durSec, cfg);
            chunkWavs.add(wav);
            chunkRanges.add(new long[]{startSec, durSec * 1000});
        }
    }

    /**
     * FFmpeg 抽取音频片段并转 16kHz mono PCM WAV。
     * 可选 loudnorm + highpass 预处理：统一响度（轻声段识别率提升）并滤除
     * 80Hz 以下低频隆隆声。分片场景对每片独立归一化到同一目标响度。
     *
     * @param startSec 起始秒（0 表示从头）
     * @param durSec   时长秒
     */
    private void toMono16kWav(Path input, Path output, long startSec, long durSec,
                              AppProperties.Asr.WhisperCpp cfg) throws Exception {
        CommandRunner.run(Command.builder()
                .add("ffmpeg", "-y", "-loglevel", "error")
                .add("-ss", String.valueOf(startSec))
                .add("-t", String.valueOf(durSec))
                .add("-i", input.toString())
                .add(preprocessArgs(cfg))
                .add("-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le")
                .add(output.toString())
                .timeout(300_000)
                .build());
    }

    /** 预处理 filter：loudnorm（统一响度）+ highpass（滤低频隆隆声） */
    private static List<String> preprocessArgs(AppProperties.Asr.WhisperCpp cfg) {
        if (!cfg.isPreprocess()) {
            return List.of();
        }
        return List.of("-af", "loudnorm=I=-16:TP=-1.5:LRA=11,highpass=f=80");
    }

    // ────────────────────────────── whisper-cli 调用 ──────────────────────────────

    /**
     * 对单个 WAV 片段调用 whisper-cli，返回 segment 列表（含 token 级时间戳）。
     */
    private List<ObjectNode> runWhisperCli(Task task, Path wav, Path outputDir, String language,
                                           AppProperties.Asr.WhisperCpp cfg,
                                           Path modelPath, Path vadModelPath) throws Exception {
        Path outputBase = outputDir.resolve("asr_" + System.nanoTime());
        List<String> command = new ArrayList<>();
        command.add("whisper-cli");
        command.add("-m");
        command.add(modelPath.toString());
        command.add("-f");
        command.add(wav.toString());
        command.add("-ojf");
        command.add("-of");
        command.add(outputBase.toString());
        command.add("-t");
        command.add(String.valueOf(cfg.getThreads() > 0 ? cfg.getThreads() : 4));
        command.add("-bs");
        command.add(String.valueOf(cfg.getBeamSize() > 0 ? cfg.getBeamSize() : 5));
        if (language != null && !language.isBlank() && !"auto".equalsIgnoreCase(language)) {
            command.add("-l");
            command.add(language);
        }
        if (vadModelPath != null) {
            command.add("--vad");
            command.add("-vm");
            command.add(vadModelPath.toString());
            // 分离后的人声音频干净，调低阈值捕获轻声段；加大最小静音时长防止句中被切断
            command.add("--vad-threshold");
            command.add(String.valueOf(cfg.getVadThreshold() > 0 ? cfg.getVadThreshold() : 0.3));
            command.add("--vad-min-silence-duration-ms");
            command.add(String.valueOf(cfg.getVadMinSilenceMs() > 0 ? cfg.getVadMinSilenceMs() : 200));
        }
        if (cfg.isNoContext()) {
            // 关闭跨段历史条件化（--max-context 0 = 不携带上一段文本），防止重复循环与幻觉
            command.add("--max-context");
            command.add("0");
        }
        if (cfg.getEntropyThold() > 0) {
            // 拒绝低熵（重复）输出，抑制静音处幻觉
            command.add("--entropy-thold");
            command.add(String.valueOf(cfg.getEntropyThold()));
        }
        if (cfg.getLogprobThold() < 0) {
            // 拒绝低置信度片段
            command.add("--logprob-thold");
            command.add(String.valueOf(cfg.getLogprobThold()));
        }
        if (cfg.getPrompt() != null && !cfg.getPrompt().isBlank()) {
            command.add("--prompt");
            command.add(cfg.getPrompt());
        }
        command.add("-np");

        log.info("whisper.cpp 执行分片识别：task={}, chunk={}", task.getId(), wav.getFileName());
        long cmdTimeout = cfg.getTimeoutMs() > 0 ? cfg.getTimeoutMs() : 0L;
        CommandRunner.run(Command.builder()
                .add(command)
                .timeout(cmdTimeout)
                .workDir(outputDir)
                .onLine(line -> log.debug("[whisper-cli] {}", line))
                .build());

        Path whisperJson = Path.of(outputBase + JSON_SUFFIX);
        if (!Files.exists(whisperJson)) {
            throw new RuntimeException("whisper.cpp 输出文件不存在：" + whisperJson);
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(whisperJson));
            List<ObjectNode> segments = new ArrayList<>();
            JsonNode transcription = root.path("transcription");
            if (transcription.isArray()) {
                for (JsonNode seg : transcription) {
                    segments.add((ObjectNode) seg);
                }
            }
            return segments;
        } finally {
            Files.deleteIfExists(whisperJson);
        }
    }

    /**
     * 将分片内 segment（含其 tokens）的 offsets 统一加上分片起始时间偏移。
     */
    private void shiftSegmentOffsets(ObjectNode seg, long offsetMs) {
        JsonNode offsets = seg.get("offsets");
        if (offsets instanceof ObjectNode offObj) {
            offObj.put("from", offsets.path("from").asLong(0) + offsetMs);
            offObj.put("to", offsets.path("to").asLong(0) + offsetMs);
        }
        JsonNode tokens = seg.get("tokens");
        if (tokens != null) {
            for (JsonNode tok : tokens) {
                JsonNode tokOffsets = tok.get("offsets");
                if (tokOffsets instanceof ObjectNode tokOffObj) {
                    tokOffObj.put("from", tokOffsets.path("from").asLong(0) + offsetMs);
                    tokOffObj.put("to", tokOffsets.path("to").asLong(0) + offsetMs);
                }
            }
        }
    }

    // ────────────────────────────── ASR JSON 构建 ──────────────────────────────

    /**
     * 将 whisper.cpp 的 segment 列表转换为标准 ASR JSON。
     * <p>
     * whisper.cpp JSON 结构：{ "transcription": [ { "timestamps": {...},
     * "offsets": { "from": ms, "to": ms }, "text": "...", "tokens": [
     * { "text": "...", "offsets": { "from": ms, "to": ms }, "id": N, "p": 0.9 } ] } ] }
     * <p>
     * 输出格式与 {@link OnnxWhisperRecognizer} 一致：
     * <pre>{@code
     * {
     *   "audio_info": { "source": "...", "duration": ms },
     *   "result": {
     *     "text": "...",
     *     "utterances": [
     *       { "text": "...", "start_time": ms, "end_time": ms, "speaker": "1",
     *         "words": [ { "text": "...", "start_time": ms, "end_time": ms } ] }
     *     ]
     *   }
     * }
     * }</pre>
     */
    private ObjectNode buildAsrJson(List<ObjectNode> segments, Path audioPath) {
        // 将所有 token 展平为词级条目（whisper.cpp token 级时间戳；英文长词可能拆 token，
        // 但 UtteranceProcessor 按句末标点重分段，不影响句子完整性）
        List<TokenWord> allWords = new ArrayList<>();
        for (ObjectNode seg : segments) {
            JsonNode tokens = seg.path("tokens");
            if (tokens.isArray() && !tokens.isEmpty()) {
                for (JsonNode tok : tokens) {
                    long from = tok.path("offsets").path("from").asLong(-1);
                    long to = tok.path("offsets").path("to").asLong(-1);
                    if (from >= 0 && to >= from) {
                        String text = tok.path("text").asText("");
                        // 剥离 whisper.cpp 控制 token（[_BEG_] / [_TT_nnn] 等），不进入 ASR 文本
                        if (isWhisperCppControlToken(text)) {
                            continue;
                        }
                        allWords.add(new TokenWord(text, from, to));
                    }
                }
            } else {
                // 无 token 时间戳时回退 segment 级
                long from = seg.path("offsets").path("from").asLong(0);
                long to = seg.path("offsets").path("to").asLong(0);
                allWords.add(new TokenWord(seg.path("text").asText("").trim(), from, to));
            }
        }

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode audioInfo = objectMapper.createObjectNode();
        audioInfo.put("source", audioPath.toString());
        root.set("audio_info", audioInfo);

        ObjectNode resultObj = objectMapper.createObjectNode();
        StringBuilder fullText = new StringBuilder();
        ArrayNode utterances = objectMapper.createArrayNode();

        if (!allWords.isEmpty()) {
            List<TokenWord> batch = new ArrayList<>();
            for (TokenWord w : allWords) {
                if (batch.isEmpty()) {
                    batch.add(w);
                    continue;
                }
                TokenWord last = batch.get(batch.size() - 1);
                long gap = w.startMs - last.endMs;
                boolean sentenceEnd = isSentenceEnd(last.text);
                long wouldDur = w.endMs - batch.get(0).startMs;
                int wouldChars = charsOf(batch) + w.text.length();
                boolean overLimit = wouldDur > MAX_UTT_DUR_MS || wouldChars > MAX_UTT_CHARS;

                if (sentenceEnd || gap > GAP_SPLIT_MS || overLimit) {
                    if (overLimit && !sentenceEnd && gap <= GAP_SPLIT_MS) {
                        int splitIdx = findBestSplitIndex(batch);
                        if (splitIdx > 0) {
                            utterances.add(buildUtterance(new ArrayList<>(batch.subList(0, splitIdx)), fullText));
                            batch = new ArrayList<>(batch.subList(splitIdx, batch.size()));
                            batch.add(w);
                            continue;
                        }
                    }
                    utterances.add(buildUtterance(batch, fullText));
                    batch.clear();
                    batch.add(w);
                } else {
                    batch.add(w);
                }
            }
            if (!batch.isEmpty()) {
                utterances.add(buildUtterance(batch, fullText));
            }
        }

        resultObj.put("text", fullText.toString().trim());
        resultObj.set("utterances", utterances);
        root.set("result", resultObj);
        return root;
    }

    private static boolean isSentenceEnd(String token) {
        if (token == null || token.isEmpty()) return false;
        char last = token.charAt(token.length() - 1);
        return last == '.' || last == '!' || last == '?'
                || last == '。' || last == '！' || last == '？'
                || last == '\n';
    }

    /** whisper.cpp 控制 token（[_BEG_] / [_TT_nnn] 等），不出现在转录文本中 */
    private static boolean isWhisperCppControlToken(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if ("[_BEG_]".equals(t)) {
            return true;
        }
        if (t.startsWith("[_TT_") && t.endsWith("]")) {
            String num = t.substring(5, t.length() - 1);
            return !num.isEmpty() && num.chars().allMatch(Character::isDigit);
        }
        return false;
    }

    private static boolean isClauseEnd(String token) {
        if (token == null || token.isEmpty()) return false;
        char last = token.charAt(token.length() - 1);
        return last == ',' || last == ';' || last == '、' || last == '：' || last == '；';
    }

    private static int charsOf(List<TokenWord> words) {
        int sum = 0;
        for (TokenWord w : words) sum += w.text.length();
        return sum;
    }

    private static int findBestSplitIndex(List<TokenWord> batch) {
        int bestClause = -1;
        long bestClauseGap = -1;
        int bestGap = -1;
        long bestGapVal = -1;
        for (int i = 1; i < batch.size(); i++) {
            long gap = batch.get(i).startMs - batch.get(i - 1).endMs;
            if (gap < MIN_INTERNAL_GAP_MS) continue;
            if (gap > bestGapVal) {
                bestGapVal = gap;
                bestGap = i;
            }
            if (isClauseEnd(batch.get(i - 1).text) && gap > bestClauseGap) {
                bestClauseGap = gap;
                bestClause = i;
            }
        }
        if (bestClause >= 0) return bestClause;
        return bestGap;
    }

    private ObjectNode buildUtterance(List<TokenWord> words, StringBuilder fullText) {
        StringBuilder text = new StringBuilder();
        for (TokenWord w : words) {
            if (text.length() > 0) text.append(" ");
            text.append(w.text);
        }

        ObjectNode utt = objectMapper.createObjectNode();
        utt.put("text", text.toString());
        utt.put("start_time", words.get(0).startMs);
        utt.put("end_time", words.get(words.size() - 1).endMs);
        utt.put("speaker", "1");

        ArrayNode wordArr = objectMapper.createArrayNode();
        for (TokenWord w : words) {
            ObjectNode wn = objectMapper.createObjectNode();
            wn.put("text", w.text);
            wn.put("start_time", w.startMs);
            wn.put("end_time", w.endMs);
            wordArr.add(wn);
        }
        utt.set("words", wordArr);

        if (fullText != null) {
            if (fullText.length() > 0) fullText.append(" ");
            fullText.append(text);
        }
        return utt;
    }

    /** 词级时间戳记录（whisper.cpp token 级 / segment 级统一抽象） */
    private record TokenWord(String text, long startMs, long endMs) {
    }
}
