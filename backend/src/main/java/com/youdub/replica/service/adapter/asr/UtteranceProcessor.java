package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ASR 句子后处理器。
 * <p>
 * 管线中 ASR 阶段输出的是 whisper 等引擎的原始 transcription，包含听写层面的断句和单词时间戳。
 * 原始断句往往不准确（一句被切碎、词语粘连到不该属于的句子），
 * 本类负责将这些原始听写结果修正为符合语法边界的完整句子，输出到 {@code asr_fixed.json} 供后续翻译使用。
 * <p>
 * 处理流程：
 * <ol>
 *   <li>检查是否有单词级时间戳（{@code utterances[].words[]}）；</li>
 *   <li>有时间戳 → 按句尾标点逐词重分段（{@link #reSegmentByWords}），同时规范化空格、拆分超长句；</li>
 *   <li>无时间戳 → 回退到从句片段合并（{@link #mergeFragments}），将 ASR 误切分的碎片拼接回去；</li>
 *   <li>上述任一流程失败 → 最终回退，仅对原始时间戳做 padding。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UtteranceProcessor {

    /**
     * 合并后句子的字符数上限。超过此阈值的句子不再参与合并，避免字幕过长。
     * 当重分段路径启用时，超过此阈值且含逗号的句子会在最后一个不超限的逗号处切分。
     */
    private static final int MAX_MERGE_CHARS = 160;

    /**
     * 时间 padding 值（毫秒）。起始时间前移，结束时间后移，避免字幕紧贴语音边缘。
     */
    private static final int PADDING_START = 100;
    private static final int PADDING_END = 300;

    private final ObjectMapper objectMapper;

    /** 内部数据载体，在 mergeFragments 中传递句子文本及其时间信息。 */
    private record Segment(String text, long startTime, long endTime, String speaker) {}

    // ════════════════════════════════════════════════════════════
    //  公共入口
    // ════════════════════════════════════════════════════════════

    /**
     * 以完整的 ASR JSON 对象为输入，返回处理后的结果对象。
     * <p>
     * 先尝试单词级重分段；如果失败（无单词时间戳 or 异常），
     * 依次降级为：从句合并 → 普通时间 padding。
     *
     * @param asrRoot  ASR 结果根节点（包含 {@code result.utterances[]}）
     * @return 处理后的结果对象（固定包含 {@code result.utterances[]}）
     */
    public ObjectNode processAsrResult(JsonNode asrRoot) {
        JsonNode utterances = asrRoot.path("result").path("utterances");

        // 路径 A：单词级重分段（最精确）
        if (hasWordTimestamps(utterances)) {
            try {
                ArrayNode reSegmented = reSegmentByWords(utterances);
                return wrapResult(reSegmented);
            } catch (Exception e) {
                log.warn("单词级重分段异常，降级到从句合并：{}", e.getMessage());
            }
        }

        // 路径 B：从句片段合并（无单词时间戳时的回退）
        try {
            ArrayNode merged = mergeUtterances(utterances);
            return wrapResult(merged);
        } catch (Exception e) {
            log.warn("从句合并异常，降级到时间 padding：{}", e.getMessage());
        }

        // 路径 C：仅做时间 padding（最后兜底）
        return applyTimePadding(asrRoot.deepCopy());
    }

    /** 包装 utterances 数组为标准响应结构。 */
    private ObjectNode wrapResult(ArrayNode utterances) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode result = root.putObject("result");
        result.set("utterances", utterances);
        return root;
    }

    /** 遍历原节点，仅对每条 utterance 的时间戳做 padding。 */
    private ObjectNode applyTimePadding(ObjectNode root) {
        JsonNode utterances = root.path("result").path("utterances");
        if (utterances.isArray()) {
            for (JsonNode u : utterances) {
                if (u instanceof ObjectNode obj) {
                    long start = obj.path("start_time").asLong(0);
                    long end = obj.path("end_time").asLong(0);
                    obj.put("start_time", Math.max(0, start - PADDING_START));
                    obj.put("end_time", end + PADDING_END);
                }
            }
        }
        return root;
    }

    // ════════════════════════════════════════════════════════════
    //  路径判断
    // ════════════════════════════════════════════════════════════

    /** 检查 utterances 中是否包含单词级时间戳（{@code words[]} 数组）。 */
    private boolean hasWordTimestamps(JsonNode utterances) {
        for (JsonNode u : utterances) {
            if (u.path("words").isArray() && !u.path("words").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════
    //  路径 A：单词级重分段
    // ════════════════════════════════════════════════════════════

    /**
     * 利用单词级时间戳按句子边界重新分段。
     * <p>
     * whisper 返回的 {@code words[]} 包含每个单词的起止时间和文本。
     * 原始 ASR 的 utterances 断句往往不准（如将 "operations" 分到下一段），
     * 本方法抛弃原始断句，以句尾标点为界重新拼接单词，保证每个 utterance 都是完整的语法句子。
     * <p>
     * 输出额外处理：连续空格归一化、超长句逗号切分。
     */
    private ArrayNode reSegmentByWords(JsonNode utterances) {
        ArrayNode result = objectMapper.createArrayNode();
        StringBuilder currentText = new StringBuilder();
        long sentenceStart = -1;
        long sentenceEnd = -1;
        String speaker = "1";

        // 遍历所有 utterance 的 words，逐词拼接，遇句尾标点切分
        for (JsonNode u : utterances) {
            speaker = u.path("speaker").asText("1");
            JsonNode words = u.path("words");
            if (!words.isArray()) continue;

            for (JsonNode w : words) {
                String word = w.path("text").asText("");
                if (word.isEmpty()) continue;

                // 记录首单词的开始时间
                if (sentenceStart < 0) {
                    sentenceStart = w.path("start_time").asLong(0);
                }
                // 词间加空格（不用原 text，避免多余空格）。
                // 注意：whisper 有时把连词符 "-"、域名点 "." 等标点作为独立 token 返回，
                // 这类 token 应紧贴前一个词不加空格（如 "zero"+"-cost" → "zero-cost"）。
                if (currentText.length() > 0 && needsLeadingSpace(word)) {
                    currentText.append(" ");
                }
                currentText.append(word);
                sentenceEnd = w.path("end_time").asLong(0);

                // 单词以句尾标点结尾 → 认为当前句子结束，写出 utterance
                if (isSentenceEnd(word.stripTrailing())) {
                    flushUtterance(currentText, sentenceStart, sentenceEnd, speaker, result);
                    currentText.setLength(0);
                    sentenceStart = -1;
                    sentenceEnd = -1;
                }
            }
        }

        // 末尾可能有一段没有句尾标点的残余文本（如 ASR 被截断）
        if (!currentText.isEmpty()) {
            flushUtterance(currentText, sentenceStart, sentenceEnd, speaker, result);
        }

        int originalCount = countNonEmpty(utterances);
        if (result.size() != originalCount) {
            log.info("按句子边界重新分段：原始 {} 段 → {} 句", originalCount, result.size());
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════
    //  路径 B：从句片段合并
    // ════════════════════════════════════════════════════════════

    /**
     * 将原始 utterances 解析为 {@link Segment} 列表，执行从句合并，再转回 ArrayNode。
     * <p>
     * 仅当单词级时间戳不可用时走此路径。
     */
    private ArrayNode mergeUtterances(JsonNode utterances) {
        // 将 JSON 节点解析为内部 Segment 列表
        List<Segment> segments = new ArrayList<>();
        for (JsonNode u : utterances) {
            String text = u.path("text").asText("").trim();
            if (text.isEmpty()) continue;
            segments.add(new Segment(text,
                    u.path("start_time").asLong(0),
                    u.path("end_time").asLong(0),
                    u.path("speaker").asText("1")));
        }

        // 合并从句碎片
        segments = mergeFragments(segments);

        // 转回 ArrayNode 并加上时间 padding
        ArrayNode result = objectMapper.createArrayNode();
        for (Segment s : segments) {
            result.add(buildUtteranceObj(s.text, s.startTime, s.endTime, s.speaker));
        }
        return result;
    }

    /**
     * 合并从句碎片：将 ASR 误断开的句子重新拼接。
     * <p>
     * 合并条件同时满足：
     * <ul>
     *   <li>当前句不以句尾标点结尾（说明句子未结束）；</li>
     *   <li>下一句以小写字母开头（说明是当前句的延续）；</li>
     *   <li>合并后总长度不超过 {@link #MAX_MERGE_CHARS}。</li>
     * </ul>
     * <p>
     * 如果下一句中包含句尾标点（如 "inside the method. This is..."），
     * 则只合并到第一个句尾标点处，剩余部分作为独立句子保留（防止过度合并）。
     */
    private List<Segment> mergeFragments(List<Segment> items) {
        if (items.isEmpty()) return items;
        List<Segment> result = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            String currentText = items.get(i).text;
            // 如果当前句已达上限，不参与合并，直接保留
            if (currentText.length() > MAX_MERGE_CHARS) {
                result.add(items.get(i));
                i++;
                continue;
            }

            StringBuilder text = new StringBuilder(currentText);
            long startTime = items.get(i).startTime;
            long endTime = items.get(i).endTime;
            String speaker = items.get(i).speaker;
            int j = i;

            // 当合并超出阈值时，被裁剪部分从下一句拆分出来作为独立片段
            String pendingRemainder = null;
            long pendingRemainderStart = 0;
            long pendingRemainderEnd = 0;
            String pendingRemainderSpeaker = null;

            // 向前遍历，满足合并条件时才合并
            while (j < items.size() - 1
                    && !items.get(j + 1).text.isEmpty()
                    && Character.isLowerCase(items.get(j + 1).text.charAt(0))
                    && !isSentenceEnd(text.toString().stripTrailing())) {

                String nextText = items.get(j + 1).text;
                int splitPos = findSentenceBoundary(nextText);

                String mergePart;
                long mergeEnd;

                if (splitPos > 0) {
                    // 下一句中包含句尾标点：只合并到标点位置
                    mergePart = nextText.substring(0, splitPos).stripTrailing();
                    String rest = nextText.substring(splitPos).stripLeading();

                    // 按字符长度比例估算时间分割点
                    double ratio = (double) mergePart.length() / nextText.length();
                    long duration = items.get(j + 1).endTime - items.get(j + 1).startTime;
                    mergeEnd = items.get(j + 1).startTime + (long) (duration * ratio);

                    int mergedLen = text.length() + 1 + mergePart.length();
                    if (mergedLen > MAX_MERGE_CHARS) break;

                    text.append(" ").append(mergePart);
                    endTime = mergeEnd;
                    j++;

                    // 标点之后的内容作为独立的 pending 片段
                    if (!rest.isEmpty()) {
                        pendingRemainder = rest;
                        pendingRemainderStart = mergeEnd;
                        pendingRemainderEnd = items.get(j).endTime;
                        pendingRemainderSpeaker = items.get(j).speaker;
                    }
                    break;
                } else {
                    // 下一句不含句尾标点：整个合并
                    mergePart = nextText;
                    int mergedLen = text.length() + 1 + mergePart.length();
                    if (mergedLen > MAX_MERGE_CHARS) break;
                    j++;
                    text.append(" ").append(mergePart);
                    endTime = items.get(j).endTime;
                }
            }

            result.add(new Segment(text.toString(), startTime, endTime, speaker));
            if (pendingRemainder != null) {
                result.add(new Segment(pendingRemainder, pendingRemainderStart, pendingRemainderEnd, pendingRemainderSpeaker));
            }
            i = j + 1;
        }

        if (result.size() != items.size()) {
            log.info("合并了 {} 个从句片段（原始 {} 句 → 合并后 {} 句）",
                    items.size() - result.size(), items.size(), result.size());
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════
    //  句子写出与辅助方法
    // ════════════════════════════════════════════════════════════

    /**
     * 将当前句子写出为 utterance 节点：
     * ① 归一化连续空格；
     * ② 若超过 {@link #MAX_MERGE_CHARS} 且含逗号，在最后一个不超限的逗号处切分为两句；
     * ③ 应用时间 padding 后添加到输出数组。
     */
    private void flushUtterance(StringBuilder textBuilder, long sentenceStart, long sentenceEnd,
                                String speaker, ArrayNode output) {
        // 归一化：去除首尾空格、中间连续空格 → 单空格
        String text = textBuilder.toString().strip().replaceAll("\\s{2,}", " ");
        if (text.isEmpty()) return;

        // 超长且含逗号 → 尝试在逗号处切分
        if (text.length() > MAX_MERGE_CHARS && text.contains(",")) {
            int splitIdx = findCommaSplitIndex(text);
            if (splitIdx > 0) {
                String firstPart = text.substring(0, splitIdx + 1).strip(); // 保留逗号，避免语义断裂
                String secondPart = text.substring(splitIdx + 1).strip();

                // 按文本长度比例估算切分点时间
                double ratio = (double) firstPart.length() / text.length();
                long midTime = sentenceStart + (long) ((sentenceEnd - sentenceStart) * ratio);

                if (!firstPart.isEmpty()) {
                    output.add(buildUtteranceObj(firstPart, sentenceStart, midTime, speaker));
                }
                if (!secondPart.isEmpty()) {
                    output.add(buildUtteranceObj(secondPart, midTime, sentenceEnd, speaker));
                }
                return;
            }
        }

        output.add(buildUtteranceObj(text, sentenceStart, sentenceEnd, speaker));
    }

    /**
     * 在 {@link #MAX_MERGE_CHARS} 范围内找最后一个逗号位置。
     * 如果所有逗号都在阈值之后，回退到第一个逗号。
     */
    private int findCommaSplitIndex(String text) {
        int bestIdx = -1;
        int idx = -1;
        while ((idx = text.indexOf(',', idx + 1)) >= 0) {
            if (idx <= MAX_MERGE_CHARS) {
                bestIdx = idx;
            }
            if (idx > MAX_MERGE_CHARS) break;
        }
        if (bestIdx < 0) {
            bestIdx = text.indexOf(',');
        }
        return bestIdx;
    }

    /** 构建单条 utterance 的 JSON 节点（含时间 padding）。 */
    private ObjectNode buildUtteranceObj(String text, long startTime, long endTime, String speaker) {
        ObjectNode u = objectMapper.createObjectNode();
        u.put("text", text);
        // 时间 padding：起始前移 100ms，结束延后 300ms，避免字幕紧贴语音边缘
        u.put("start_time", Math.max(0, startTime - PADDING_START));
        u.put("end_time", endTime + PADDING_END);
        u.put("speaker", speaker);
        return u;
    }

    /** 判断文本是否以句尾标点结尾。支持中英文标点。 */
    private static boolean isSentenceEnd(String text) {
        return text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
                || text.endsWith("。") || text.endsWith("！") || text.endsWith("？");
    }

    /** 在文本中查找第一个句尾标点的位置（标点后紧跟空格或文本结束算有效边界）。 */
    static int findSentenceBoundary(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                if (i + 1 >= text.length() || text.charAt(i + 1) == ' ') {
                    return i + 1;
                }
            } else if (c == '。' || c == '！' || c == '？') {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 判断 word 之前是否需要加空格。
     * <p>
     * whisper 的单词级时间戳有时将标点作为独立 token 返回（如连词符 "-"、域名点 ".org"、百分号 "%"），
     * 如果照常加空格，会产生 "zero -cost"、"brilliant .org" 之类的不自然文本。
     * 以标点开头的词应紧贴前一个词，不加空格。
     */
    private static boolean needsLeadingSpace(String word) {
        char c = word.charAt(0);
        // 字母/数字/引号/左括号 → 应加空格；标点开头 → 贴前一词
        return Character.isLetterOrDigit(c)
                || c == '"' || c == '\''
                || c == '(' || c == '[' || c == '{';
    }

    /** 统计 utterances 数组中非空文本的条目数。 */
    private static int countNonEmpty(JsonNode utterances) {
        int count = 0;
        for (JsonNode u : utterances) {
            if (!u.path("text").asText("").trim().isEmpty()) count++;
        }
        return count;
    }
}
