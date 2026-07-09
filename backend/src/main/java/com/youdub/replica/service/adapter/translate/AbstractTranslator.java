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


}
