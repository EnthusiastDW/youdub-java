package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class UtteranceProcessorRealFileTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UtteranceProcessor processor = new UtteranceProcessor(mapper);

    @Test
    void processRealAsrData() throws Exception {
        ClassPathResource resource = new ClassPathResource("asr_fixture.json");
        JsonNode root = mapper.readTree(resource.getInputStream());
        JsonNode originalUtts = root.path("result").path("utterances");
        assertTrue(originalUtts.isArray() && !originalUtts.isEmpty());

        System.out.println("=".repeat(90));
        System.out.println("原始 Utterances（" + originalUtts.size() + " 条）");
        System.out.println("=".repeat(90));
        printUtterances("  ", originalUtts);

        JsonNode processed = processor.processAsrResult(root);
        ArrayNode resultUtts = (ArrayNode) processed.path("result").path("utterances");

        int delta = originalUtts.size() - resultUtts.size();
        System.out.println();
        System.out.println("=".repeat(90));
        System.out.println("处理后 Utterances（" + resultUtts.size() + " 条"
                + (delta > 0 ? "，减少了 " + delta + " 条" : "，计数未减少，但文本已重新分配") + "）");
        System.out.println("=".repeat(90));
        printUtterances("  ", resultUtts);

        assertFalse(resultUtts.isEmpty());
        for (int i = 0; i < resultUtts.size(); i++) {
            String text = resultUtts.get(i).path("text").asText("");
            assertFalse(text.isEmpty());
            assertFalse(text.isBlank());
        }
    }

    private static void printUtterances(String prefix, JsonNode items) {
        for (int i = 0; i < items.size(); i++) {
            JsonNode u = items.get(i);
            String text = u.path("text").asText("");
            long start = u.path("start_time").asLong();
            long end = u.path("end_time").asLong();
            int len = text.length();
            String over = len > 160 ? "  ← 超160字符" : "";
            System.out.printf("%s[%02d] %6d→%6d (%3dch) | %s%s%n",
                    prefix, i, start, end, len, text, over);
        }
    }
}
