package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UtteranceProcessor#processAsrResult} 的单元测试。
 * 无 words 时间戳时走 mergeFragments 回退路径，有时戳时走单词级重分段路径。
 */
class UtteranceProcessorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UtteranceProcessor processor = new UtteranceProcessor(mapper);

    /* ────────── 基础逻辑（回退路径：无 word 时间戳 → mergeFragments） ────────── */

    @Test
    void emptyArray_returnsEmpty() {
        ArrayNode result = process(mapper.createArrayNode());
        assertEquals(0, result.size());
    }

    @Test
    void singleItem_unchanged() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "Hello world.", 0, 1000);
        ArrayNode result = process(items);
        assertEquals(1, result.size());
        assertEquals("Hello world.", result.get(0).path("text").asText());
        assertEquals(0, result.get(0).path("start_time").asLong());
        assertTrue(result.get(0).path("end_time").asLong() > 1000);
    }

    @Test
    void endsWithPeriod_noMerge() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "Hello world.", 0, 1000);
        addUtterance(items, "Goodbye.", 1000, 2000);
        assertEquals(2, process(items).size());
    }

    @Test
    void nextNotLowercase_noMerge() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "hello,", 0, 500);
        addUtterance(items, "World", 500, 1000);
        assertEquals(2, process(items).size());
    }

    @Test
    void endsWithExclamation_noMerge() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "Stop!", 0, 500);
        addUtterance(items, "next part", 500, 1000);
        assertEquals(2, process(items).size());
    }

    @Test
    void noPunctuationEnding_lowercaseNext_merges() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "as it already checked that we are only doing legal operations", 0, 3000);
        addUtterance(items, "inside the method. This is guaranteed because we only have a reference to self at our disposal.", 3000, 6000);
        ArrayNode result = process(items);
        assertEquals(2, result.size());
        assertEquals("as it already checked that we are only doing legal operations inside the method.", result.get(0).path("text").asText());
        assertTrue(result.get(1).path("text").asText().startsWith("This is guaranteed"));
        assertEquals(0, result.get(0).path("start_time").asLong());
        assertEquals(6000 + 300, result.get(1).path("end_time").asLong());
    }

    @Test
    void noPunctuation_followedByPeriod_mergesThenStops() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "first fragment no punctuation", 0, 1000);
        addUtterance(items, "second part here", 1000, 2000);
        addUtterance(items, "Third sentence starts uppercase.", 2000, 3000);
        ArrayNode result = process(items);
        assertEquals(2, result.size());
        assertEquals("first fragment no punctuation second part here", result.get(0).path("text").asText());
        assertEquals("Third sentence starts uppercase.", result.get(1).path("text").asText());
    }

    /* ────────── 正常合并（短句） ────────── */

    @Test
    void shortCommaEnding_mergesWithNext() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "before you deploy,", 0, 1000);
        addUtterance(items, "like organizing your components", 1000, 2000);
        ArrayNode result = process(items);
        assertEquals(1, result.size());
        assertEquals("before you deploy, like organizing your components", result.get(0).path("text").asText());
    }

    @Test
    void multipleFragments_allWithinLimit() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "first part,", 0, 300);
        addUtterance(items, "second part,", 300, 600);
        addUtterance(items, "third part", 600, 900);
        ArrayNode result = process(items);
        assertEquals(1, result.size());
        assertEquals("first part, second part, third part", result.get(0).path("text").asText());
    }

    /* ────────── 长度保护 ────────── */

    @Test
    void firstFragmentExceedsMax_skipMerge() {
        String longText = "A".repeat(161);
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, longText + ",", 0, 1000);
        addUtterance(items, "next fragment", 1000, 2000);
        ArrayNode result = process(items);
        assertEquals(2, result.size());
        assertTrue(result.get(0).path("text").asText().length() > 160);
    }

    @Test
    void mergedResultExceedsMax_stopBeforeThreshold() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "short text,", 0, 500);
        addUtterance(items, "this continuation is long enough to push the total way over the one hundred and sixty character threshold for max merge chars when combined together with the first fragment", 500, 2000);
        ArrayNode result = process(items);
        assertEquals(2, result.size());
    }

    @Test
    void firstMergeOk_secondWouldExceed_stops() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "alpha,", 0, 200);
        addUtterance(items, "beta,", 200, 400);
        addUtterance(items, "gamma but then a very very long continuation that exceeds the limit of one hundred and sixty characters threshold easily when merged together so it would break up here", 400, 2000);
        ArrayNode result = process(items);
        assertEquals(2, result.size());
        assertEquals("alpha, beta,", result.get(0).path("text").asText());
        assertEquals(0, result.get(0).path("start_time").asLong());
        assertEquals(400 + 300, result.get(0).path("end_time").asLong());
    }

    /* ────────── 混合场景 ────────── */

    @Test
    void mixed_someMergeSomeSkip() {
        String longText = "A".repeat(161);
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "short,", 0, 300);
        addUtterance(items, "still short,", 300, 600);
        addUtterance(items, longText + ",", 600, 3000);
        addUtterance(items, "last", 3000, 3200);
        ArrayNode result = process(items);
        assertEquals(3, result.size());
        assertEquals("short, still short,", result.get(0).path("text").asText());
        assertTrue(result.get(1).path("text").asText().length() > 160);
        assertEquals("last", result.get(2).path("text").asText());
    }

    /* ────────── 时间戳传递 ────────── */

    @Test
    void mergedUtterance_usesFirstStart_lastEnd() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "part one,", 100, 500);
        addUtterance(items, "part two,", 500, 900);
        addUtterance(items, "part three", 900, 1300);
        ArrayNode result = process(items);
        assertEquals(1, result.size());
        assertEquals(Math.max(0, 100 - 100), result.get(0).path("start_time").asLong());
        assertEquals(1300 + 300, result.get(0).path("end_time").asLong());
    }

    /* ────────── words 时间戳路径 ────────── */

    @Test
    void withWordTimestamps_reSegmentsBySentenceEnd() {
        ArrayNode items = mapper.createArrayNode();
        ObjectNode u = items.addObject();
        u.put("text", "hello world. next sentence");
        u.put("start_time", 0);
        u.put("end_time", 2000);
        u.put("speaker", "1");
        ArrayNode words = u.putArray("words");
        addWord(words, "hello", 0, 400);
        addWord(words, "world.", 400, 800);
        addWord(words, "next", 800, 1200);
        addWord(words, "sentence", 1200, 2000);

        ArrayNode result = process(items);
        assertEquals(2, result.size());
        assertEquals("hello world.", result.get(0).path("text").asText());
        assertEquals("next sentence", result.get(1).path("text").asText());
    }

    /* ────────── helper ────────── */

    /** 调用处理器，返回处理后的 utterances 数组。 */
    private ArrayNode process(ArrayNode utterances) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode result = root.putObject("result");
        result.set("utterances", utterances);
        JsonNode processed = processor.processAsrResult(root).path("result").path("utterances");
        return processed.isArray() ? (ArrayNode) processed : mapper.createArrayNode();
    }

    private void addUtterance(ArrayNode arr, String text, long startMs, long endMs) {
        ObjectNode u = arr.addObject();
        u.put("text", text);
        u.put("start_time", startMs);
        u.put("end_time", endMs);
        u.put("speaker", "1");
    }

    private void addWord(ArrayNode words, String text, long startMs, long endMs) {
        ObjectNode w = words.addObject();
        w.put("text", text);
        w.put("start_time", startMs);
        w.put("end_time", endMs);
    }
}
