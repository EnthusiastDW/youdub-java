package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WhisperCppRecognizer} 的 token 展平与特殊 token 过滤逻辑测试。
 */
class WhisperCppRecognizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private WhisperCppRecognizer recognizer;

    @BeforeEach
    void setUp() {
        recognizer = new WhisperCppRecognizer(mapper, null);
    }

    @Test
    void controlTokens_areFilteredFromAsrText() throws Exception {
        ArrayNode segments = mapper.createArrayNode();

        ObjectNode seg = mapper.createObjectNode();
        ArrayNode tokens = mapper.createArrayNode();
        tokens.add(token(" [_BEG_]", 0, 10));
        tokens.add(token(" Hello", 10, 300));
        tokens.add(token(" world.", 300, 600));
        tokens.add(token(" [_TT_155]", 600, 610));
        tokens.add(token(" Next", 610, 800));
        seg.set("tokens", tokens);
        segments.add(seg);

        ObjectNode result = buildAsrJson(segments);

        String text = result.path("result").path("text").asText();
        assertFalse(text.contains("_BEG_"), "text should not contain _BEG_: " + text);
        assertFalse(text.contains("_TT_"), "text should not contain _TT_: " + text);
        assertTrue(text.contains("Hello"), "expected Hello in: " + text);
        assertTrue(text.contains("world."), "expected world. in: " + text);
        assertTrue(text.contains("Next"), "expected Next in: " + text);
    }

    @Test
    void controlTokenOffsets_doNotBreakUtteranceTimestamps() throws Exception {
        ArrayNode segments = mapper.createArrayNode();

        ObjectNode seg = mapper.createObjectNode();
        ArrayNode tokens = mapper.createArrayNode();
        tokens.add(token(" [_BEG_]", 0, 5));
        tokens.add(token(" Only", 100, 250));
        tokens.add(token(" real", 250, 400));
        tokens.add(token(" words.", 400, 550));
        tokens.add(token(" [_TT_42]", 555, 560));
        seg.set("tokens", tokens);
        segments.add(seg);

        ObjectNode result = buildAsrJson(segments);
        ArrayNode utts = (ArrayNode) result.path("result").path("utterances");
        assertEquals(1, utts.size());
        ObjectNode utt = (ObjectNode) utts.get(0);
        assertEquals(100, utt.path("start_time").asLong());
        assertEquals(550, utt.path("end_time").asLong());
        assertEquals(3, utt.path("words").size());
    }

    @Test
    void plainText_withoutControlTokens_isKept() throws Exception {
        ArrayNode segments = mapper.createArrayNode();
        ObjectNode seg = mapper.createObjectNode();
        ArrayNode tokens = mapper.createArrayNode();
        tokens.add(token(" Hello", 0, 200));
        tokens.add(token(" world.", 200, 400));
        seg.set("tokens", tokens);
        segments.add(seg);

        ObjectNode result = buildAsrJson(segments);
        String text = result.path("result").path("text").asText();
        assertTrue(text.contains("Hello"), "expected Hello in: " + text);
        assertTrue(text.contains("world."), "expected world. in: " + text);
    }

    @Test
    void fallbackToSegmentText_whenNoTokens() throws Exception {
        ArrayNode segments = mapper.createArrayNode();
        ObjectNode seg = mapper.createObjectNode();
        seg.put("text", "Segment text without tokens.");
        ObjectNode offsets = mapper.createObjectNode();
        offsets.put("from", 0);
        offsets.put("to", 3000);
        seg.set("offsets", offsets);
        segments.add(seg);

        ObjectNode result = buildAsrJson(segments);
        String text = result.path("result").path("text").asText();
        assertEquals("Segment text without tokens.", text);
    }

    private ObjectNode token(String text, long from, long to) {
        ObjectNode tok = mapper.createObjectNode();
        tok.put("text", text);
        ObjectNode offsets = mapper.createObjectNode();
        offsets.put("from", from);
        offsets.put("to", to);
        tok.set("offsets", offsets);
        return tok;
    }

    private ObjectNode buildAsrJson(ArrayNode segments) throws Exception {
        Method m = WhisperCppRecognizer.class.getDeclaredMethod("buildAsrJson", java.util.List.class, Path.class);
        m.setAccessible(true);
        java.util.List<ObjectNode> segList = new java.util.ArrayList<>();
        for (var s : segments) segList.add((ObjectNode) s);
        return (ObjectNode) m.invoke(recognizer, segList, Path.of("/tmp/test.wav"));
    }
}
