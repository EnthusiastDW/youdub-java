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
    private static final int MAX_MERGE_CHARS = 80;

    /**
     * 合并从句片段：如果某句以逗号结尾而下一句以小写开头，
     * 说明 ASR 将一句话断成了两段，需要合并后再翻译，避免 LLM 脑补。
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
            while (j < items.size() - 1
                    && items.get(j).text.endsWith(",")
                    && !items.get(j + 1).text.isEmpty()
                    && Character.isLowerCase(items.get(j + 1).text.charAt(0))) {
                String nextText = items.get(j + 1).text;
                int mergedLen = text.length() + 1 + nextText.length();
                if (mergedLen > MAX_MERGE_CHARS) {
                    break;
                }
                j++;
                text.append(" ").append(nextText);
                endTime = items.get(j).endTime;
            }
            result.add(new Utterance(text.toString(), startTime, endTime, speaker));
            i = j + 1;
        }
        if (result.size() != items.size()) {
            log.info("合并了 {} 个从句片段（原始 {} 句 → 合并后 {} 句）",
                    items.size() - result.size(), items.size(), result.size());
        }
        return result;
    }
}
