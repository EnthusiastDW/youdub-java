package site.dengwei.onnxruntime.whisper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import site.dengwei.onnxruntime.OnnxRuntimeEnv;
import site.dengwei.onnxruntime.audio.WavAudio;
import site.dengwei.onnxruntime.whisper.WhisperModel.Segment;
import site.dengwei.onnxruntime.whisper.WhisperModel.TranscriptionResult;
import site.dengwei.onnxruntime.whisper.WhisperModel.Word;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对比优化前后的 ASR 转录结果。
 * 使用 whisper-tiny.en 模型处理指定音频，生成新版 asr.json 并与 asr-origin.json 比较。
 *
 * 标记为 {@code @Tag("integration")}，需用 {@code mvn test -Dgroups=integration} 运行。
 */
@Tag("integration")
class WhisperModelComparisonTest {

    // ── 路径（从项目根目录计算） ──────────────────────────────────

    private static Path projectRoot() {
        Path cwd = Paths.get(".").toAbsolutePath().normalize();
        // 如果从 onnxruntime 目录运行，上一级就是项目根
        if (cwd.endsWith("onnxruntime") || cwd.toString().contains("onnxruntime")) {
            return cwd.getParent();
        }
        return cwd;
    }

    private static final Path PROJ = projectRoot();
    private static final Path MODEL_DIR = PROJ.resolve("data/whisper-models/whisper-tiny.en");
    private static final String TASK_DIR = "backend/workfolder/local"
            + "/Rusts For Loop Is Smarter Than You Think  Advanced Rust Part 12__3d9b99ae8ecc43ea";
    private static final Path AUDIO_FILE = PROJ.resolve(TASK_DIR + "/media/audio_vocals.wav");
    private static final Path ORIGIN_FILE = PROJ.resolve(TASK_DIR + "/metadata/asr-origin.json");
    private static final Path NEW_FILE = PROJ.resolve(TASK_DIR + "/metadata/asr.json");

    // ── 测试 ──────────────────────────────────────────────────────

    @Test
    void compareOptimizedTranscription() throws Exception {
        // 环境检查
        assumeTrue(OnnxRuntimeEnv.isNativeAvailable(), "ONNX Runtime 本机库不可用");
        assumeTrue(Files.exists(MODEL_DIR.resolve("encoder_model.onnx")), "模型文件不存在: " + MODEL_DIR);
        assumeTrue(Files.exists(AUDIO_FILE), "音频文件不存在: " + AUDIO_FILE);
        assumeTrue(Files.exists(ORIGIN_FILE), "原始 ASR 文件不存在: " + ORIGIN_FILE);

        // 直接构造（避免 ensureFiles 联网下载不必须的 decoder_model_merged.onnx）
        System.out.println("\n=== 加载模型: " + MODEL_DIR + " ===");
        long t0 = System.currentTimeMillis();
        try (WhisperModel whisper = new WhisperModel(MODEL_DIR, "en")) {

            // 2. 读取音频
            System.out.println("\n=== 读取音频: " + AUDIO_FILE + " ===");
            WavAudio wav = WavAudio.read(AUDIO_FILE);
            float[] audio = wav.channels() > 1 ? wav.toMono().samples() : wav.samples();
            int sampleRate = wav.sampleRate();
            System.out.println("音频: " + (double) audio.length / sampleRate + "s, "
                    + sampleRate + "Hz, " + wav.channels() + "ch");

            // 3. 转录
            System.out.println("\n=== 开始转录（优化版分片 + 跨 chunk 上下文）===");
            long t1 = System.currentTimeMillis();
            TranscriptionResult result = whisper.transcribeChunked(audio, sampleRate);
            long elapsed = System.currentTimeMillis() - t1;
            int wordCount = result.segments().stream().mapToInt(s -> s.words().size()).sum();
            System.out.println("转录完成: " + elapsed + "ms, "
                    + "segments=" + result.segments().size()
                    + ", words=" + wordCount);
            System.out.println("全文:\n" + result.fullText());

            // 4. 构建新版 ASR JSON
            System.out.println("\n=== 构建新版 ASR JSON ===");
            long durationMs = (long) ((double) audio.length / sampleRate * 1000);
            String newJson = buildAsrJson(result, AUDIO_FILE, durationMs).toString(2);
            Files.writeString(NEW_FILE, newJson);
            System.out.println("已保存: " + NEW_FILE);

            // 5. 读取原始 ASR JSON
            System.out.println("\n=== 加载原始 ASR JSON ===");
            String originJson = Files.readString(ORIGIN_FILE);
            JSONObject originRoot = new JSONObject(originJson);
            JSONObject newRoot = new JSONObject(newJson);

            // 6. 对比
            System.out.println("\n═══════════════════════ 对比分析 ═══════════════════════\n");
            compareResults(originRoot, newRoot);
        }
    }

    // ────────────────────────────── JSON 构建 ──────────────────────────────

    private static JSONObject buildAsrJson(TranscriptionResult result, Path audioPath, long durationMs) {
        JSONObject root = new JSONObject();

        JSONObject audioInfo = new JSONObject();
        audioInfo.put("source", audioPath.toString());
        audioInfo.put("duration", durationMs);
        root.put("audio_info", audioInfo);

        JSONObject resultObj = new JSONObject();
        resultObj.put("text", result.fullText());

        // 所有单词展平，按 500ms + 句尾标点分组
        List<Word> allWords = new ArrayList<>();
        for (Segment seg : result.segments()) {
            allWords.addAll(seg.words());
        }

        JSONArray utterances = new JSONArray();
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
                        utterances.put(buildUtterance(batch));
                        batch.clear();
                        batch.add(w);
                    }
                }
            }
            if (!batch.isEmpty()) {
                utterances.put(buildUtterance(batch));
            }
        }

        resultObj.put("utterances", utterances);
        root.put("result", resultObj);
        return root;
    }

    private static boolean isSentenceEnd(String token) {
        if (token == null || token.isEmpty()) return false;
        char last = token.charAt(token.length() - 1);
        return last == '.' || last == '!' || last == '?'
                || last == '。' || last == '！' || last == '？'
                || last == '\n';
    }

    private static JSONObject buildUtterance(List<Word> words) {
        StringBuilder text = new StringBuilder();
        for (Word w : words) {
            if (text.length() > 0) text.append(" ");
            text.append(w.text());
        }

        JSONObject utt = new JSONObject();
        utt.put("text", text.toString());
        utt.put("start_time", words.get(0).startMs());
        utt.put("end_time", words.get(words.size() - 1).endMs());
        utt.put("speaker", "1");

        JSONArray wordArr = new JSONArray();
        for (Word w : words) {
            JSONObject wn = new JSONObject();
            wn.put("text", w.text());
            wn.put("start_time", w.startMs());
            wn.put("end_time", w.endMs());
            wordArr.put(wn);
        }
        utt.put("words", wordArr);
        return utt;
    }

    // ────────────────────────────── 对比分析 ──────────────────────────────

    private void compareResults(JSONObject originRoot, JSONObject newRoot) {
        JSONObject originResult = originRoot.getJSONObject("result");
        JSONObject newResult = newRoot.getJSONObject("result");

        // 基本信息
        String originText = originResult.getString("text");
        String newText = newResult.getString("text");
        JSONArray originUtterances = originResult.getJSONArray("utterances");
        JSONArray newUtterances = newResult.getJSONArray("utterances");
        long originDuration = originRoot.getJSONObject("audio_info").getLong("duration");
        long newDuration = newRoot.getJSONObject("audio_info").getLong("duration");

        System.out.println("┌─────────────────────┬─────────────────┬─────────────────┐");
        System.out.printf("│ %-21s│ %-15s │ %-15s │\n", "指标", "Origin", "优化后");
        System.out.println("├─────────────────────┼─────────────────┼─────────────────┤");
        System.out.printf("│ %-21s│ %-15s │ %-15s │\n", "段落数", originUtterances.length(), newUtterances.length());
        System.out.printf("│ %-21s│ %-15s │ %-15s │\n", "全文长度(字符)", originText.length(), newText.length());
        System.out.printf("│ %-21s│ %-15s │ %-15s │\n", "音频时长(ms)", originDuration, newDuration);
        System.out.printf("│ %-21s│ %-15s │ %-15s │\n", "末段结束(ms)", lastEnd(originUtterances), lastEnd(newUtterances));
        System.out.println("└─────────────────────┴─────────────────┴─────────────────┘");

        // 尾部垃圾检查：最后 5 段
        System.out.println("\n── 尾部 5 段对比 ──");
        checkTail(originUtterances, "Origin", 5);
        checkTail(newUtterances, "优化后", 5);

        // 文本对比：差异行
        System.out.println("\n── 文本差异对比 ──");
        String[] originLines = originText.split("(?<=[.!?。！？])\\s*");
        String[] newLines = newText.split("(?<=[.!?。！？])\\s*");
        int maxLines = Math.max(originLines.length, newLines.length);
        int diffCount = 0;
        for (int i = 0; i < maxLines; i++) {
            String ol = i < originLines.length ? originLines[i].trim() : "(缺失)";
            String nl = i < newLines.length ? newLines[i].trim() : "(缺失)";
            if (!ol.equals(nl)) {
                diffCount++;
                if (diffCount <= 20) {
                    System.out.println("  差异 #" + diffCount + ":");
                    System.out.println("    Origin: " + truncate(ol, 80));
                    System.out.println("    优化后: " + truncate(nl, 80));
                }
            }
        }
        System.out.println("共 " + diffCount + " 处文本差异" + (diffCount > 20 ? "(仅显示前20)" : ""));

        // 重复检测
        System.out.println("\n── 重复段检测 ──");
        int originDup = countDuplicates(originUtterances);
        int newDup = countDuplicates(newUtterances);
        System.out.println("  Origin 重复段: " + originDup);
        System.out.println("  优化后重复段: " + newDup);

        // 统计特征
        System.out.println("\n── 时间戳精度 ──");
        System.out.println("  Origin 末单词精度: " + wordTimePrecision(originUtterances));
        System.out.println("  优化后末单词精度: " + wordTimePrecision(newUtterances));

        // 概要评价
        System.out.println("\n── 结论 ──");
        if (newUtterances.length() > originUtterances.length() * 1.5) {
            System.out.println("  ⚠ 段落数增加超过 50%，分段可能过碎");
        }
        if (newText.length() > originText.length() * 1.2) {
            System.out.println("  ⚠ 文本长度增加超过 20%，可能有幻觉/重复");
        }
        if (newText.length() < originText.length() * 0.8) {
            System.out.println("  ⚠ 文本长度减少超过 20%，可能有内容遗漏");
        }
        if (lastEnd(newUtterances) < originDuration - 5000) {
            System.out.println("  ⚠ 末段时间比音频总长少 " + (originDuration - lastEnd(newUtterances)) + "ms，尾部被截断");
        }
        System.out.println("  ✓ 末段结束于 " + lastEnd(newUtterances) + "ms (音频总长 " + originDuration + "ms)");
    }

    // ────────────────────────────── 辅助 ──────────────────────────────

    private static long lastEnd(JSONArray utterances) {
        if (utterances.length() == 0) return 0;
        return utterances.getJSONObject(utterances.length() - 1).getLong("end_time");
    }

    private static void checkTail(JSONArray utterances, String label, int n) {
        int start = Math.max(0, utterances.length() - n);
        for (int i = start; i < utterances.length(); i++) {
            JSONObject u = utterances.getJSONObject(i);
            String txt = u.optString("text", "");
            System.out.println("  " + label + " [" + i + "]: "
                    + u.getLong("start_time") + "-" + u.getLong("end_time") + "ms"
                    + " \"" + truncate(txt, 60) + "\"");
        }
    }

    private static int countDuplicates(JSONArray utterances) {
        Map<String, Integer> seen = new HashMap<>();
        int dup = 0;
        for (int i = 0; i < utterances.length(); i++) {
            String text = utterances.getJSONObject(i).optString("text", "").trim().toLowerCase();
            if (text.isEmpty()) continue;
            int prev = seen.getOrDefault(text, -1);
            if (prev >= 0 && i - prev <= 3) dup++;
            seen.put(text, i);
        }
        return dup;
    }

    /** 分析单词级时间戳的最小非零间隔（反映精度） */
    private static String wordTimePrecision(JSONArray utterances) {
        long minGap = Long.MAX_VALUE;
        int samples = 0;
        for (int i = 0; i < utterances.length() && i < 20; i++) {
            JSONArray words = utterances.getJSONObject(i).optJSONArray("words");
            if (words == null) continue;
            for (int j = 1; j < words.length(); j++) {
                long gap = words.getJSONObject(j).getLong("start_time")
                        - words.getJSONObject(j - 1).getLong("start_time");
                if (gap > 0 && gap < minGap) minGap = gap;
                samples++;
            }
        }
        return "min_gap=" + minGap + "ms (samples=" + samples + ")";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
