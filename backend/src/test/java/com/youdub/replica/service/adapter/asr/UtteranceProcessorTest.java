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

    @Test
    void withWordTimestamps_longSentenceWithComma_splitsAtComma() {
        // 构造超过 MAX_MERGE_CHARS(160) 且含逗号的句子，应在逗号处切分
        String firstPart = "This is a very long sentence that needs to be split because it exceeds the maximum merge chars limit,";
        String secondPart = "and this is the continuation after the comma that forms the second utterance.";
        String full = firstPart + " " + secondPart;
        assertTrue(full.length() > 160);

        ArrayNode items = mapper.createArrayNode();
        ObjectNode u = items.addObject();
        u.put("text", full);
        u.put("start_time", 0);
        u.put("end_time", 5000);
        u.put("speaker", "1");
        ArrayNode words = u.putArray("words");
        addWord(words, "This", 0, 200);
        addWord(words, "is", 200, 300);
        addWord(words, "a", 300, 350);
        addWord(words, "very", 350, 500);
        addWord(words, "long", 500, 650);
        addWord(words, "sentence", 650, 900);
        addWord(words, "that", 900, 1050);
        addWord(words, "needs", 1050, 1250);
        addWord(words, "to", 1250, 1350);
        addWord(words, "be", 1350, 1420);
        addWord(words, "split", 1420, 1600);
        addWord(words, "because", 1600, 1850);
        addWord(words, "it", 1850, 1950);
        addWord(words, "exceeds", 1950, 2200);
        addWord(words, "the", 2200, 2300);
        addWord(words, "maximum", 2300, 2550);
        addWord(words, "merge", 2550, 2750);
        addWord(words, "chars", 2750, 2950);
        addWord(words, "limit,", 2950, 3100);
        addWord(words, "and", 3200, 3350);
        addWord(words, "this", 3350, 3500);
        addWord(words, "is", 3500, 3600);
        addWord(words, "the", 3600, 3700);
        addWord(words, "continuation", 3700, 4000);
        addWord(words, "after", 4000, 4200);
        addWord(words, "the", 4200, 4300);
        addWord(words, "comma", 4300, 4500);
        addWord(words, "that", 4500, 4650);
        addWord(words, "forms", 4650, 4850);
        addWord(words, "the", 4850, 4920);
        addWord(words, "second", 4920, 5000);
        addWord(words, "utterance.", 5000, 5200);

        ArrayNode result = process(items);
        assertEquals(2, result.size());
        assertTrue(result.get(0).path("text").asText().endsWith("limit,"),
                "第一句应以逗号结尾: " + result.get(0).path("text").asText());
        assertTrue(result.get(1).path("text").asText().startsWith("and"),
                "第二句应以 and 开头: " + result.get(1).path("text").asText());
        // 验证时间戳：midTime 是第一句文本切分点的估算时间
        long firstEnd = result.get(0).path("end_time").asLong();   // = midTime + PADDING_END
        long secondStart = result.get(1).path("start_time").asLong(); // = midTime - PADDING_START
        assertTrue(firstEnd > secondStart,
                "第一句结束应晚于第二句开始（padding 造成重叠）: end=" + firstEnd + ", start=" + secondStart);
        // midTime = (end - 300 + start + 100) / 2，应在 [2000, 3500] 范围内
        long reconstructedMid = (firstEnd - 300 + secondStart + 100) / 2;
        assertTrue(reconstructedMid >= 2000 && reconstructedMid <= 3500,
                "切分点估算应在合理范围: " + reconstructedMid);
    }

    @Test
    void withWordTimestamps_specialTokens_attachCorrectly() {
        // whisper 将连词符 "-"、域名点 "."、百分号 "%" 作为独立 token 返回
        ArrayNode items = mapper.createArrayNode();
        ObjectNode u = items.addObject();
        u.put("text", "zero -cost brilliant .org 50 %");
        u.put("start_time", 0);
        u.put("end_time", 3000);
        u.put("speaker", "1");
        ArrayNode words = u.putArray("words");
        addWord(words, "zero", 0, 500);
        addWord(words, "-cost", 500, 700);
        addWord(words, "brilliant", 700, 1200);
        addWord(words, ".org", 1200, 1500);
        addWord(words, "50", 1500, 2000);
        addWord(words, "%", 2000, 2500);
        addWord(words, "normal.", 2500, 3000);

        ArrayNode result = process(items);
        assertEquals(1, result.size());
        String text = result.get(0).path("text").asText();
        assertEquals("zero-cost brilliant.org 50% normal.", text);
    }

    @Test
    void withWordTimestamps_throwException_fallsBackToMerge() {
        // 构造异常的 utterances（words 非数组）触发降级
        ArrayNode items = mapper.createArrayNode();
        ObjectNode u = items.addObject();
        u.put("text", "hello world");
        u.put("start_time", 0);
        u.put("end_time", 1000);
        u.put("speaker", "1");
        // words 字段设为一个字符串（非数组），导致 reSegmentByWords 内部处理异常
        u.put("words", "not_an_array");

        ArrayNode result = process(items);
        assertEquals(1, result.size());
        assertEquals("hello world", result.get(0).path("text").asText());
    }

    /* ────────── 中文 ────────── */

    @Test
    void chineseText_withPeriod_endsSentence() {
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "今天天气真好。", 0, 1000);
        addUtterance(items, "我们去公园吧。", 1000, 2000);
        ArrayNode result = process(items);
        assertEquals(2, result.size());
        assertEquals("今天天气真好。", result.get(0).path("text").asText());
    }

    @Test
    void chineseText_noPunctuation_doesNotMerge() {
        // 中文无标点且下句以中文开头 → Character.isLowerCase() 对中文返回 false，
        // 所以不会触发合并（mergeFragments 仅对以小写字母开头的下句合并，适用于英文场景）
        ArrayNode items = mapper.createArrayNode();
        addUtterance(items, "我们讨论了", 0, 500);
        addUtterance(items, "几个重要的方案", 500, 1000);
        ArrayNode result = process(items);
        assertEquals(2, result.size());
    }

    @Test
    void chineseWordTimestamps_reSegmentsByChinesePunctuation() {
        ArrayNode items = mapper.createArrayNode();
        ObjectNode u = items.addObject();
        u.put("text", "你好世界！我们来了");
        u.put("start_time", 0);
        u.put("end_time", 3000);
        u.put("speaker", "1");
        ArrayNode words = u.putArray("words");
        addWord(words, "你好世界！", 0, 1000);
        addWord(words, "我们", 1000, 1500);
        addWord(words, "来了", 1500, 3000);

        ArrayNode result = process(items);
        assertEquals(2, result.size());
        assertEquals("你好世界！", result.get(0).path("text").asText());
        assertEquals("我们 来了", result.get(1).path("text").asText());
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
