package com.youdub.replica.service.adapter.translate;


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
