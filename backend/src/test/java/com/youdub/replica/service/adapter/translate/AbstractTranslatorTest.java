package com.youdub.replica.service.adapter.translate;

import com.youdub.replica.model.entity.Task;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AbstractTranslator#mergeFragments(List)} 的单元测试。
 * 重点关注合并后句子长度不超过 {@code MAX_MERGE_CHARS}（80字符）。
 */
class AbstractTranslatorTest {

    private final TestTranslator translator = new TestTranslator();

    /* ────────── 基础逻辑 ────────── */

    @Test
    void emptyList_returnsEmpty() {
        assertTrue(translator.mergeFragments(List.of()).isEmpty());
    }

    @Test
    void singleItem_unchanged() {
        var result = translator.mergeFragments(List.of(utterance("Hello world.", 0, 1000)));
        assertEquals(1, result.size());
        assertEquals("Hello world.", result.get(0).text());
        assertEquals(0, result.get(0).startTime());
        assertEquals(1000, result.get(0).endTime());
    }

    @Test
    void noCommaEnding_noMerge() {
        var items = List.of(
                utterance("Hello world.", 0, 1000),
                utterance("Goodbye.", 1000, 2000)
        );
        var result = translator.mergeFragments(items);
        assertEquals(2, result.size());
    }

    @Test
    void nextNotLowercase_noMerge() {
        var items = List.of(
                utterance("hello,", 0, 500),
                utterance("World", 500, 1000)
        );
        var result = translator.mergeFragments(items);
        assertEquals(2, result.size());
    }

    @Test
    void nextEmpty_skipsMerge() {
        var items = List.of(
                utterance("hello,", 0, 500),
                utterance("", 500, 1000)
        );
        var result = translator.mergeFragments(items);
        assertEquals(2, result.size());
    }

    /* ────────── 正常合并（短句） ────────── */

    @Test
    void shortCommaEnding_mergesWithNext() {
        var items = List.of(
                utterance("before you deploy,", 0, 1000),
                utterance("like organizing your components", 1000, 2000)
        );
        var result = translator.mergeFragments(items);
        assertEquals(1, result.size());
        assertEquals("before you deploy, like organizing your components", result.get(0).text());
        assertEquals(0, result.get(0).startTime());
        assertEquals(2000, result.get(0).endTime());
    }

    @Test
    void multipleFragments_allWithinLimit() {
        var items = List.of(
                utterance("first part,", 0, 300),
                utterance("second part,", 300, 600),
                utterance("third part", 600, 900)
        );
        var result = translator.mergeFragments(items);
        assertEquals(1, result.size());
        assertEquals("first part, second part, third part", result.get(0).text());
        assertEquals(0, result.get(0).startTime());
        assertEquals(900, result.get(0).endTime());
    }

    /* ────────── 长度保护 ────────── */

    @Test
    void firstFragmentExceedsMax_skipMerge() {
        String longText = "A".repeat(81);
        var items = List.of(
                utterance(longText + ",", 0, 1000),
                utterance("next fragment", 1000, 2000)
        );
        var result = translator.mergeFragments(items);
        assertEquals(2, result.size());
        assertEquals(longText + ",", result.get(0).text());
    }

    @Test
    void mergedResultExceedsMax_stopBeforeThreshold() {
        var items = List.of(
                utterance("short text,", 0, 500),
                utterance("this continuation is long enough to push the total way over the eighty character threshold together", 500, 2000)
        );
        var result = translator.mergeFragments(items);
        assertEquals(2, result.size());
    }

    @Test
    void firstMergeOk_secondWouldExceed_stops() {
        var items = List.of(
                utterance("alpha,", 0, 200),
                utterance("beta,", 200, 400),
                utterance("gamma but then a very long continuation that exceeds the limit of eighty characters easily", 400, 2000)
        );
        var result = translator.mergeFragments(items);
        assertEquals(2, result.size());
        assertEquals("alpha, beta,", result.get(0).text());
        assertEquals(0, result.get(0).startTime());
        assertEquals(400, result.get(0).endTime());
    }

    /* ────────── 混合场景 ────────── */

    @Test
    void mixed_someMergeSomeSkip() {
        var items = List.of(
                utterance("short,", 0, 300),                                          // merged
                utterance("still short,", 300, 600),                                   // merged
                utterance("this sentence is already very long and exceeds the maximum merge threshold of eighty characters so it stays alone,", 600, 3000),  // >80, skip
                utterance("last", 3000, 3200)                                          // no comma, no merge
        );
        var result = translator.mergeFragments(items);
        assertEquals(3, result.size());
        assertEquals("short, still short,", result.get(0).text());
        assertTrue(result.get(1).text().length() > 80);
        assertEquals(result.get(1).startTime(), 600);
        assertEquals(result.get(2).text(), "last");
    }

    /* ────────── 时间戳传递 ────────── */

    @Test
    void mergedUtterance_usesFirstStart_lastEnd() {
        var items = List.of(
                utterance("part one,", 100, 500),
                utterance("part two,", 500, 900),
                utterance("part three", 900, 1300)
        );
        var result = translator.mergeFragments(items);
        assertEquals(1, result.size());
        assertEquals(100, result.get(0).startTime());
        assertEquals(1300, result.get(0).endTime());
    }

    /* ────────── helper ────────── */

    private static AbstractTranslator.Utterance utterance(String text, long startMs, long endMs) {
        return new AbstractTranslator.Utterance(text, startMs, endMs, "1");
    }

    /**
     * 仅用于暴露 {@link AbstractTranslator#mergeFragments(List)} 供测试。
     */
    private static class TestTranslator extends AbstractTranslator {
        @Override
        public void translate(Task task, Path asrPath, Path outputDir, String model, String srcLang, String dstLang) {
        }

        @Override
        public String translateText(String text, String srcLang, String dstLang) {
            return text;
        }

        @Override
        public List<Utterance> mergeFragments(List<Utterance> items) {
            return super.mergeFragments(items);
        }
    }
}
