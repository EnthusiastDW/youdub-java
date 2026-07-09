package com.youdub.replica.service.adapter.translate;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public abstract class AbstractTranslator implements Translator {

    protected record Utterance(String text, long startTime, long endTime, String speaker) {
    }

    @FunctionalInterface
    protected interface Summarizer {
        String summarize(String fullText, String targetLang) throws Exception;
    }

    /**
     * 用 LLM 对英文原文生成结构化中文小结，写入 summary.md。
     * 放在翻译前执行，避免等待逐句翻译完成。
     */
    protected void generateSummary(String fullText, Path outputDir, String targetLang,
                                   Summarizer summarizer) throws Exception {
        Path summaryFile = outputDir.resolve("summary.md");
        if (Files.exists(summaryFile)) {
            log.info("summary.md 已存在，跳过：{}", summaryFile);
            return;
        }
        if (fullText == null || fullText.isBlank()) {
            log.warn("原文为空，跳过总结生成");
            return;
        }
        log.info("生成英文原文的中文小结：原文长度={}字符", fullText.length());
        long t0 = System.currentTimeMillis();
        String summary = summarizer.summarize(fullText, targetLang);
        Files.writeString(summaryFile, summary);
        log.info("summary.md 已生成：{}（耗时={}ms）", summaryFile, System.currentTimeMillis() - t0);
    }

    /**
     * 字符数超过此阈值的句子不再参与合并，避免最终字幕过长。
     */
    protected static final int MAX_MERGE_CHARS = 160;

    /**
     * 合并从句片段：如果某句不以句尾标点结尾，而下一句以小写开头，
     * 说明 ASR 将一句话断成了两段，需要合并后再翻译，避免 LLM 脑补。
     * 比如 "operations" 被分到句尾但实际属于下一句开头的情况。
     * <p>
     * 当要合并的下一个片段中包含句尾标点（如 "inside the method. This is..."），
     * 则只合并到第一个句尾标点位置，剩余部分保持为独立句子，避免合并后句子过长。
     * 但如果句子已经较长（超过 {@link #MAX_MERGE_CHARS}），则不再合并，
     * 防止累积产生超长字幕影响观看。
     */
    protected List<Utterance> mergeFragments(List<Utterance> items) {
        if (items.isEmpty()) return items;
        List<Utterance> result = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            String currentText = items.get(i).text;
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
            String pendingRemainder = null;
            long pendingRemainderStart = 0;
            long pendingRemainderEnd = 0;
            String pendingRemainderSpeaker = null;

            while (j < items.size() - 1
                    && !items.get(j + 1).text.isEmpty()
                    && Character.isLowerCase(items.get(j + 1).text.charAt(0))
                    && !isSentenceEnd(text.toString().stripTrailing())) {

                String nextText = items.get(j + 1).text;
                int splitPos = findSentenceBoundary(nextText);

                String mergePart;
                long mergeEnd;

                if (splitPos > 0) {
                    mergePart = nextText.substring(0, splitPos).stripTrailing();
                    String rest = nextText.substring(splitPos).stripLeading();
                    double ratio = (double) mergePart.length() / nextText.length();
                    long duration = items.get(j + 1).endTime - items.get(j + 1).startTime;
                    mergeEnd = items.get(j + 1).startTime + (long) (duration * ratio);

                    int mergedLen = text.length() + 1 + mergePart.length();
                    if (mergedLen > MAX_MERGE_CHARS) break;

                    text.append(" ").append(mergePart);
                    endTime = mergeEnd;
                    j++;

                    if (!rest.isEmpty()) {
                        pendingRemainder = rest;
                        pendingRemainderStart = mergeEnd;
                        pendingRemainderEnd = items.get(j).endTime;
                        pendingRemainderSpeaker = items.get(j).speaker;
                    }
                    break;
                } else {
                    mergePart = nextText;
                    int mergedLen = text.length() + 1 + mergePart.length();
                    if (mergedLen > MAX_MERGE_CHARS) break;
                    j++;
                    text.append(" ").append(mergePart);
                    endTime = items.get(j).endTime;
                }
            }

            result.add(new Utterance(text.toString(), startTime, endTime, speaker));
            if (pendingRemainder != null) {
                result.add(new Utterance(pendingRemainder, pendingRemainderStart, pendingRemainderEnd, pendingRemainderSpeaker));
            }
            i = j + 1;
        }
        if (result.size() != items.size()) {
            log.info("合并了 {} 个从句片段（原始 {} 句 → 合并后 {} 句）",
                    items.size() - result.size(), items.size(), result.size());
        }
        return result;
    }

    /**
     * 利用单词级时间戳按句子边界重新分段。
     * <p>
     * 从 {@code asr.json} 的 {@code utterances[].words[]} 中遍历所有单词，
     * 遇到句尾标点（. ! ?）即产生一个新 {@link Utterance}，其时间戳由单词的首尾时间精确决定。
     * 这样 ASR 切错的句子（如将 "operations" 分到下一段）会被正确还原。
     * <p>
     * 如果单词级时间戳不可用，回退为返回原始 utterances 列表（由 {@link #mergeFragments} 兜底）。
     *
     * @param utterancesNode ASR 结果中的 utterances 数组节点
     * @return 按句子边界重新分段后的 utterance 列表
     */
    protected List<Utterance> reSegmentByWords(JsonNode utterancesNode) {
        List<Utterance> result = new ArrayList<>();
        if (!utterancesNode.isArray() || utterancesNode.isEmpty()) {
            return result;
        }

        // 检查是否有单词级时间戳可用
        boolean hasWords = false;
        for (JsonNode u : utterancesNode) {
            JsonNode words = u.path("words");
            if (words.isArray() && words.size() > 0) {
                hasWords = true;
                break;
            }
        }

        if (!hasWords) {
            // 回退：直接返回原始 utterances（mergeFragments 会兜底）
            for (JsonNode u : utterancesNode) {
                String text = u.path("text").asText("").trim();
                if (text.isEmpty()) continue;
                result.add(new Utterance(text,
                        u.path("start_time").asLong(0),
                        u.path("end_time").asLong(0),
                        u.path("speaker").asText("1")));
            }
            return result;
        }

        // 单词级重分段：遍历所有单词，在句尾标点处切分
        StringBuilder currentText = new StringBuilder();
        long sentenceStart = -1;
        long sentenceEnd = -1;
        String speaker = "1";

        for (JsonNode u : utterancesNode) {
            speaker = u.path("speaker").asText("1");
            JsonNode words = u.path("words");
            if (!words.isArray()) continue;

            for (JsonNode w : words) {
                String word = w.path("text").asText("");
                if (word.isEmpty()) continue;

                if (sentenceStart < 0) {
                    sentenceStart = w.path("start_time").asLong(0);
                }

                if (currentText.length() > 0) {
                    currentText.append(" ");
                }
                currentText.append(word);
                sentenceEnd = w.path("end_time").asLong(0);

                // 单词以句尾标点结尾 → 切分
                if (isSentenceEnd(word.stripTrailing())) {
                    String text = currentText.toString().trim();
                    if (!text.isEmpty()) {
                        result.add(new Utterance(text, sentenceStart, sentenceEnd, speaker));
                    }
                    currentText = new StringBuilder();
                    sentenceStart = -1;
                    sentenceEnd = -1;
                }
            }
        }

        // 末尾未闭合的文本（没有句尾标点）
        String remaining = currentText.toString().trim();
        if (!remaining.isEmpty()) {
            result.add(new Utterance(remaining, sentenceStart, sentenceEnd, speaker));
        }

        // 统计原始非空 utterance 数
        int originalCount = 0;
        for (JsonNode u : utterancesNode) {
            if (!u.path("text").asText("").trim().isEmpty()) originalCount++;
        }
        if (result.size() != originalCount) {
            log.info("按句子边界重新分段：原始 {} 段 → {} 句", originalCount, result.size());
        }

        return result;
    }

    private static boolean isSentenceEnd(String text) {
        return text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
                || text.endsWith("。") || text.endsWith("！") || text.endsWith("？");
    }

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
}
