package com.youdub.replica.service.adapter.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用真实 ASR JSON 文件测试 {@link AbstractTranslator#mergeFragments(List)} 的整体效果。
 * 从 classpath 读取 asr_fixture.json，模拟实际管线中的数据流，
 * 输出合并前后的句子列表，方便肉眼验证断句效果。
 */
class MergeFragmentsRealFileTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TestTranslator translator = new TestTranslator();
    private static final int MAX_CHARS = AbstractTranslator.MAX_MERGE_CHARS;

    @Test
    void mergeRealAsrData() throws Exception {
        // 1. 读取 ASR JSON fixture
        ClassPathResource resource = new ClassPathResource("asr_fixture.json");
        JsonNode root = objectMapper.readTree(resource.getInputStream());
        JsonNode utterances = root.path("result").path("utterances");
        assertTrue(utterances.isArray() && !utterances.isEmpty(), "ASR fixture 应有 utterances");

        // 2. 解析为 Utterance 列表（和 OpenAiTranslator 一样的逻辑）
        List<AbstractTranslator.Utterance> items = new ArrayList<>();
        for (JsonNode u : utterances) {
            String text = u.path("text").asText("").trim();
            if (text.isEmpty()) continue;
            items.add(new AbstractTranslator.Utterance(
                    text,
                    u.path("start_time").asLong(0),
                    u.path("end_time").asLong(0),
                    u.path("speaker").asText("1")
            ));
        }

        // 3. 打印原始列表
        System.out.println("=" .repeat(80));
        System.out.println("原始 Utterances（" + items.size() + " 条，阈值=" + MAX_CHARS + "ch）：");
        System.out.println("=" .repeat(80));
        printUtterances("  ", items, MAX_CHARS);

        // 4. 执行合并
        List<AbstractTranslator.Utterance> merged = translator.mergeFragments(items);

        // 5. 打印合并后列表
        System.out.println();
        System.out.println("=" .repeat(80));
        System.out.println("合并后 Utterances（" + merged.size() + " 条，减少了 " + (items.size() - merged.size()) + " 条）：");
        System.out.println("=" .repeat(80));
        printUtterances("  ", merged, MAX_CHARS);

        // 6. 验证基本不变性
        assertFalse(merged.isEmpty(), "合并后不应为空");
        assertEquals(items.get(0).startTime(), merged.get(0).startTime(), "第一条的起始时间应不变");

        // 7. 耗时信息
        System.out.println();
        System.out.println("缩减率: " + (items.size() - merged.size()) + "/" + items.size()
                + " = " + String.format("%.0f%%", (items.size() - merged.size()) * 100.0 / items.size()));
    }

    private static void printUtterances(String prefix, List<AbstractTranslator.Utterance> items, int threshold) {
        for (int i = 0; i < items.size(); i++) {
            AbstractTranslator.Utterance u = items.get(i);
            int len = u.text().length();
            String annotation;

            if (len > threshold) {
                annotation = "  ← ⚠ 超过 " + threshold + " 字符";
            } else if (i < items.size() - 1
                    && Character.isLowerCase(items.get(i + 1).text().charAt(0))
                    && !u.text().stripTrailing().matches(".*[.!?]$")) {
                annotation = "  ← ⌒ 可能可合并（无句尾标点 + 下句小写开头）";
            } else {
                annotation = "";
            }

            String endsWith = u.text().stripTrailing();
            String endMark = "";
            if (endsWith.endsWith(",")) endMark = " ,";
            else if (endsWith.endsWith(".")) endMark = " .";
            else if (endsWith.endsWith("!")) endMark = " !";
            else if (endsWith.endsWith("?")) endMark = " ?";

            System.out.printf("%s[%02d] %05d→%05d (%3dch)%s | %s%s%n",
                    prefix, i,
                    u.startTime(), u.endTime(),
                    len, endMark,
                    u.text(),
                    annotation);
        }
    }

    private static class TestTranslator extends AbstractTranslator {
        @Override
        public void translate(com.youdub.replica.model.entity.Task task,
                              java.nio.file.Path asrPath,
                              java.nio.file.Path outputDir,
                              String model, String srcLang, String dstLang) {
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
