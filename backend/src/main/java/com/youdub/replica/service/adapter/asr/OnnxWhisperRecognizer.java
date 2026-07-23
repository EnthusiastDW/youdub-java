package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.model.entity.Task;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import site.dengwei.onnxruntime.audio.WavAudio;
import site.dengwei.onnxruntime.whisper.WhisperModel;
import site.dengwei.onnxruntime.whisper.WhisperModel.Segment;
import site.dengwei.onnxruntime.whisper.WhisperModel.TranscriptionResult;
import site.dengwei.onnxruntime.whisper.WhisperModel.Word;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static com.youdub.replica.service.adapter.AdapterConstants.WHISPER_ONNX;

/**
 * 基于 ONNX Runtime 的 Whisper 语音识别适配器。
 * <p>
 * 使用 {@link WhisperModel} 直接加载 encoder/decoder ONNX 模型，
 * 通过 {@link WhisperModel#transcribeChunked(float[], int)} 支持任意时长音频。
 * 片间自动调用 {@code reloadDecoderSession()} 释放 decoder native 内存。
 * <p>
 * 模型变体通过环境变量 {@code WHISPER_ONNX_MODEL} 指定，
 * 默认 {@code whisper-base}（多语言），可选 {@code whisper-small} /
 * {@code whisper-medium} / {@code whisper-large-v3} 等。
 * 语言为空时自动检测。
 * 首次启动自动从 HuggingFace {@code onnx-community/{modelVariant}} 下载模型文件。
 */
@Slf4j
@Component(WHISPER_ONNX)
@RequiredArgsConstructor
public class OnnxWhisperRecognizer implements SpeechRecognizer {

    private final ObjectMapper objectMapper;

    // 懒加载 WhisperModel，不同语言重新创建
    private volatile WhisperModel model;
    private final Object initLock = new Object();
    private String currentLanguage;

    @Override
    public void transcribe(Task task, Path audioPath, Path outputDir, String language) throws Exception {
        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }
        Files.createDirectories(outputDir);

        Path asrFile = outputDir.resolve("asr.json");
        if (Files.exists(asrFile)) {
            log.info("ASR 结果已保存过，跳过：{}", asrFile);
            return;
        }

        // 读取音频（保持原始采样率，WhisperModel 内部由 MelSpectrogram 重采样到 16kHz）
        WavAudio wav = WavAudio.read(audioPath);
        float[] audio = wav.channels() > 1 ? wav.toMono().samples() : wav.samples();
        int nativeSampleRate = wav.sampleRate();

        log.info("Whisper 识别开始：task={}, audio={}, duration={}s, lang={}, sampleRate={}",
                task.getId(), audioPath, String.format("%.1f", (double) audio.length / nativeSampleRate),
                language, nativeSampleRate);

        long t0 = System.currentTimeMillis();

        // 获取或创建模型
        WhisperModel whisper = getOrCreateModel(language);

        // 转录（自动分片 + 片间释放 decoder 内存）
        TranscriptionResult result = whisper.transcribeChunked(audio, nativeSampleRate);

        long elapsed = System.currentTimeMillis() - t0;
        int wordCount = result.segments().stream().mapToInt(s -> s.words().size()).sum();
        log.info("Whisper 识别完成：task={}, segments={}, words={}, elapsed={}ms",
                task.getId(), result.segments().size(), wordCount, elapsed);

        // 构建 ASR JSON
        long durationMs = (long) ((double) audio.length / nativeSampleRate * 1000);
        ObjectNode json = buildAsrJson(result, audioPath, durationMs);

        Files.writeString(asrFile, objectMapper.writeValueAsString(json));
        log.info("ASR 结果已保存：{}", asrFile);
    }

    // ────────────────────────────── 模型生命周期 ──────────────────────────────

    private WhisperModel getOrCreateModel(String language) {
        String lang = (language != null && !language.isBlank()) ? language : "auto";
        if (model != null && lang.equals(currentLanguage)) {
            return model;
        }
        synchronized (initLock) {
            if (model != null && lang.equals(currentLanguage)) {
                return model;
            }
            // 语言变化时关闭旧模型
            if (model != null) {
                try { model.close(); } catch (Exception ignored) {}
                model = null;
            }

            String modelVariant = System.getenv("WHISPER_ONNX_MODEL");
            if (modelVariant == null || modelVariant.isBlank()) {
                modelVariant = "whisper-base";
            }
            if (!modelVariant.startsWith("whisper-")) {
                modelVariant = "whisper-" + modelVariant;
            }

            Path modelDir = Paths.get("data", "whisper-models", modelVariant).toAbsolutePath();

            log.info("加载 WhisperModel：variant={}, dir={}, lang={}", modelVariant, modelDir, lang);
            long t0 = System.currentTimeMillis();

            this.model = WhisperModel.loadOrDownload(modelDir, lang);
            this.currentLanguage = lang;

            long elapsed = System.currentTimeMillis() - t0;
            log.info("WhisperModel 加载完成：{}ms", elapsed);
            return model;
        }
    }

    @PreDestroy
    void close() {
        if (model != null) {
            try { model.close(); } catch (Exception ignored) {}
            model = null;
            log.info("WhisperModel 已释放");
        }
    }

    // ────────────────────────────── ASR JSON 构建 ──────────────────────────────

    /**
     * 将 {@link TranscriptionResult} 转换为标准 ASR JSON。
     * <p>
     * 输出格式：
     * <pre>{@code
     * {
     *   "audio_info": { "source": "...", "duration": ms },
     *   "result": {
     *     "text": "...",
     *     "utterances": [
     *       { "text": "...", "start_time": ms, "end_time": ms,
     *         "speaker": "1",
     *         "words": [ { "text": "...", "start_time": ms, "end_time": ms } ] }
     *     ]
     *   }
     * }
     * }</pre>
     */
    private ObjectNode buildAsrJson(TranscriptionResult result, Path audioPath, long durationMs) {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode audioInfo = objectMapper.createObjectNode();
        audioInfo.put("source", audioPath.toString());
        audioInfo.put("duration", durationMs);
        root.set("audio_info", audioInfo);

        ObjectNode resultObj = objectMapper.createObjectNode();
        resultObj.put("text", result.fullText());

        // 将所有单词展平，按 500ms 间隔 + 句尾标点分组为 utterances
        List<Word> allWords = new ArrayList<>();
        for (Segment seg : result.segments()) {
            allWords.addAll(seg.words());
        }

        ArrayNode utterances = objectMapper.createArrayNode();
        if (!allWords.isEmpty()) {
            List<Word> batch = new ArrayList<>();
            for (Word w : allWords) {
                if (batch.isEmpty()) {
                    batch.add(w);
                } else {
                    Word last = batch.get(batch.size() - 1);
                    long gap = w.startMs() - last.endMs();
                    boolean sentenceEnd = isSentenceEnd(last.text());
                    if (gap <= 500 && !sentenceEnd) {
                        batch.add(w);
                    } else {
                        utterances.add(buildUtterance(batch));
                        batch.clear();
                        batch.add(w);
                    }
                }
            }
            if (!batch.isEmpty()) {
                utterances.add(buildUtterance(batch));
            }
        }

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

    private ObjectNode buildUtterance(List<Word> words) {
        StringBuilder text = new StringBuilder();
        for (Word w : words) {
            if (text.length() > 0) text.append(" ");
            text.append(w.text());
        }

        ObjectNode utt = objectMapper.createObjectNode();
        utt.put("text", text.toString());
        utt.put("start_time", words.get(0).startMs());
        utt.put("end_time", words.get(words.size() - 1).endMs());
        utt.put("speaker", "1");

        ArrayNode wordArr = objectMapper.createArrayNode();
        for (Word w : words) {
            ObjectNode wn = objectMapper.createObjectNode();
            wn.put("text", w.text());
            wn.put("start_time", w.startMs());
            wn.put("end_time", w.endMs());
            wordArr.add(wn);
        }
        utt.set("words", wordArr);
        return utt;
    }
}
