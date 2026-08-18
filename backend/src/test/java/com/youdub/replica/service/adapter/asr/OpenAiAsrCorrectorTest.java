package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OpenAiAsrCorrector} 的最小编辑应用、JSON 解析与语言感知提示词测试。
 * <p>
 * 聚焦新格式（edits from→to + confidence）的契约：from 必须精确匹配且唯一、
 * 置信度低于门槛丢弃、缺失 confidence 视为确定；以及 zh/en 提示词分支。
 */
class OpenAiAsrCorrectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private OpenAiAsrCorrector corrector;

    @BeforeEach
    void setUp() {
        corrector = new OpenAiAsrCorrector(null, mapper, null);
    }

    // ════════════════════════════════════════════════════════════
    //  applyEdits — 编辑应用
    // ════════════════════════════════════════════════════════════

    @Test
    void applyEdits_singleEdit_replacesSubstring() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"intoIterator trade","to":"IntoIterator trait","confidence":0.95}]
                """);
        String result = OpenAiAsrCorrector.applyEdits(
                "The first line creates an iterator via the intoIterator trade.", edits);
        assertEquals("The first line creates an iterator via the IntoIterator trait.", result);
    }

    @Test
    void applyEdits_multipleEdits_appliedInOrder() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"intuitorator","to":"IntoIterator","confidence":0.97},
                 {"from":"method called","to":"trait named","confidence":0.9}]
                """);
        String result = OpenAiAsrCorrector.applyEdits(
                "There is a method called intuitorator to implement.", edits);
        assertEquals("There is a trait named IntoIterator to implement.", result);
    }

    @Test
    void applyEdits_lowConfidence_dropped() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"trade","to":"trait","confidence":0.3}]
                """);
        assertNull(OpenAiAsrCorrector.applyEdits("The iterator trade is important.", edits));
    }

    @Test
    void applyEdits_missingConfidence_treatedAsCertain() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"trade","to":"trait"}]
                """);
        String result = OpenAiAsrCorrector.applyEdits("The iterator trade is important.", edits);
        assertEquals("The iterator trait is important.", result);
    }

    @Test
    void applyEdits_fromNotInOriginal_skipped() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"kub ernetes","to":"Kubernetes","confidence":0.95}]
                """);
        assertNull(OpenAiAsrCorrector.applyEdits("We use docker containers.", edits));
    }

    @Test
    void applyEdits_fromAppearsMultipleTimes_skippedToAvoidMisEdit() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"in","to":"on","confidence":0.95}]
                """);
        assertNull(OpenAiAsrCorrector.applyEdits("We live in a house in the city.", edits));
    }

    @Test
    void applyEdits_emptyFrom_skipped() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"","to":"x","confidence":0.95}]
                """);
        assertNull(OpenAiAsrCorrector.applyEdits("Hello world.", edits));
    }

    @Test
    void applyEdits_fromEqualsTo_meaninglessEditSkipped() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"hello world","to":"hello world","confidence":0.99}]
                """);
        assertNull(OpenAiAsrCorrector.applyEdits("Hello world, this is a test.", edits));
    }

    @Test
    void applyEdits_overlongFrom_wholeSentenceSkipped() throws Exception {
        // 弱模型把整句塞进 from（超过 MAX_EDIT_FROM_CHARS=60 字符），应跳过防止误替换
        String wholeSentence = "you check, now let 's start with, from the beginning, you get an understanding of where we 're going";
        JsonNode edits = mapper.readTree("""
                [{"from":"%s","to":"%s","confidence":0.99}]
                """.formatted(wholeSentence, wholeSentence));
        assertNull(OpenAiAsrCorrector.applyEdits(
                "You check, now let 's start with, from the beginning, you get an understanding of where we 're going.", edits));
    }

    @Test
    void applyEdits_shortContextualFrom_stillApplied() throws Exception {
        // 带少量上下文词的 from（含空格、短于阈值）应正常应用
        JsonNode edits = mapper.readTree("""
                [{"from":"the intoIterator trade","to":"the IntoIterator trait","confidence":0.95}]
                """);
        String result = OpenAiAsrCorrector.applyEdits(
                "The first line creates an iterator via the intoIterator trade.", edits);
        assertEquals("The first line creates an iterator via the IntoIterator trait.", result);
    }

    @Test
    void applyEdits_mixedConfidence_appliesOnlyHighOnes() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"trade","to":"trait","confidence":0.9},
                 {"from":"foo","to":"bar","confidence":0.2}]
                """);
        String result = OpenAiAsrCorrector.applyEdits("The foo trade is weird.", edits);
        assertEquals("The foo trait is weird.", result);
    }

    @Test
    void applyEdits_chineseHomophone_replaces() throws Exception {
        JsonNode edits = mapper.readTree("""
                [{"from":"因该","to":"应该","confidence":0.98}]
                """);
        String result = OpenAiAsrCorrector.applyEdits("少先队员因该为老人让坐。", edits);
        assertEquals("少先队员应该为老人让坐。", result);
    }

    // ════════════════════════════════════════════════════════════
    //  parseCorrections — JSON 解析
    // ════════════════════════════════════════════════════════════

    @Test
    void parseCorrections_editsFormat_returnsCorrectedText() throws Exception {
        Map<Integer, String> originals = new HashMap<>();
        originals.put(5, "The first line creates an iterator via the intoIterator trade.");

        JsonNode root = mapper.readTree("""
                {"corrections":[{"id":5,"edits":[{"from":"intoIterator trade","to":"IntoIterator trait","confidence":0.95}]}]}
                """);
        Map<Integer, String> result = OpenAiAsrCorrector.parseCorrections(root, originals);
        assertEquals("The first line creates an iterator via the IntoIterator trait.", result.get(5));
    }

    @Test
    void parseCorrections_unknownId_ignored() throws Exception {
        Map<Integer, String> originals = new HashMap<>();
        originals.put(1, "Hello world.");

        JsonNode root = mapper.readTree("""
                {"corrections":[{"id":99,"edits":[{"from":"Hello","to":"Hi","confidence":0.9}]}]}
                """);
        Map<Integer, String> result = OpenAiAsrCorrector.parseCorrections(root, originals);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseCorrections_lowConfidence_skipped() throws Exception {
        Map<Integer, String> originals = new HashMap<>();
        originals.put(5, "The trade is important.");

        JsonNode root = mapper.readTree("""
                {"corrections":[{"id":5,"edits":[{"from":"trade","to":"trait","confidence":0.4}]}]}
                """);
        Map<Integer, String> result = OpenAiAsrCorrector.parseCorrections(root, originals);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseCorrections_oldTextFormat_backwardCompatible() throws Exception {
        Map<Integer, String> originals = new HashMap<>();
        originals.put(5, "The first line creates an iterator via the intoIterator trade.");

        JsonNode root = mapper.readTree("""
                {"corrections":[{"id":5,"text":"The first line creates an iterator via the IntoIterator trait."}]}
                """);
        Map<Integer, String> result = OpenAiAsrCorrector.parseCorrections(root, originals);
        assertEquals("The first line creates an iterator via the IntoIterator trait.", result.get(5));
    }

    @Test
    void parseCorrections_oldTextFormat_nonOverlapping_rewriteDropped() throws Exception {
        Map<Integer, String> originals = new HashMap<>();
        originals.put(5, "The first line creates an iterator via the intoIterator trade.");

        JsonNode root = mapper.readTree("""
                {"corrections":[{"id":5,"text":"This is a completely different sentence about something else."}]}
                """);
        Map<Integer, String> result = OpenAiAsrCorrector.parseCorrections(root, originals);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseCorrections_missingArray_throws() throws Exception {
        Map<Integer, String> originals = new HashMap<>();
        originals.put(1, "Hello world.");

        JsonNode root = mapper.readTree("{\"foo\":[]}");
        assertThrows(RuntimeException.class, () -> OpenAiAsrCorrector.parseCorrections(root, originals));
    }

    @Test
    void parseCorrections_emptyCorrections_returnsEmpty() throws Exception {
        Map<Integer, String> originals = new HashMap<>();
        originals.put(1, "Hello world.");

        JsonNode root = mapper.readTree("{\"corrections\":[]}");
        assertTrue(OpenAiAsrCorrector.parseCorrections(root, originals).isEmpty());
    }

    // ════════════════════════════════════════════════════════════
    //  buildSystemPrompt — 语言感知提示词
    // ════════════════════════════════════════════════════════════

    @Test
    void buildSystemPrompt_chineseLanguage_usesChineseHomophonePrompt() {
        String prompt = ReflectionTestUtils.invokeMethod(corrector, "buildSystemPrompt",
                "Rust 教程", "zh");
        assertNotNull(prompt);
        assertTrue(prompt.contains("因该"));
        assertTrue(prompt.contains("同音/近音"));
        assertTrue(prompt.contains("\"from\""));
    }

    @Test
    void buildSystemPrompt_englishLanguage_usesMinimalEditFormat() {
        String prompt = ReflectionTestUtils.invokeMethod(corrector, "buildSystemPrompt",
                "Rust tutorial", "en");
        assertNotNull(prompt);
        assertTrue(prompt.contains("MINIMAL EDIT"));
        assertTrue(prompt.contains("'from' must be an exact substring"));
        assertTrue(prompt.contains("confidence"));
    }

    @Test
    void buildSystemPrompt_zhCnVariant_treatedAsChinese() {
        String prompt = ReflectionTestUtils.invokeMethod(corrector, "buildSystemPrompt",
                null, "zh-CN");
        assertNotNull(prompt);
        assertTrue(prompt.contains("因该"));
    }

    @Test
    void buildSystemPrompt_nullLanguage_defaultsToEnglish() {
        String prompt = ReflectionTestUtils.invokeMethod(corrector, "buildSystemPrompt",
                "Rust tutorial", null);
        assertNotNull(prompt);
        assertTrue(prompt.contains("MINIMAL EDIT"));
    }

    @Test
    void buildSystemPrompt_blankLanguage_defaultsToEnglish() {
        String prompt = ReflectionTestUtils.invokeMethod(corrector, "buildSystemPrompt",
                null, "  ");
        assertNotNull(prompt);
        assertTrue(prompt.contains("MINIMAL EDIT"));
    }
}
