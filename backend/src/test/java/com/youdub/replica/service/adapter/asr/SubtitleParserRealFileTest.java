package com.youdub.replica.service.adapter.asr;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubtitleParserRealFileTest {

    @Test
    void parseRealVtt() throws Exception {
        ClassPathResource resource = new ClassPathResource("video_source.en.vtt");
        Path vttFile = resource.getFile().toPath();
        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vttFile);

        assertFalse(segments.isEmpty(), "应有字幕段");

        System.out.println("===== 前 10 段 =====");
        for (int i = 0; i < Math.min(10, segments.size()); i++) {
            SubtitleParser.Segment seg = segments.get(i);
            System.out.printf("[%04d] %06dms → %06dms (%05dms) | %s%n",
                    i, seg.getStartTimeMs(), seg.getEndTimeMs(),
                    seg.getEndTimeMs() - seg.getStartTimeMs(),
                    seg.getText());
        }
        System.out.println("===== 第 324-334 段 =====");
        for (int i = Math.max(0, segments.size() - 10); i < segments.size(); i++) {
            SubtitleParser.Segment seg = segments.get(i);
            System.out.printf("[%04d] %06dms → %06dms (%05dms) | %s%n",
                    i, seg.getStartTimeMs(), seg.getEndTimeMs(),
                    seg.getEndTimeMs() - seg.getStartTimeMs(),
                    seg.getText());
        }

        // 检查有无 <c> 或 <> 残留
        for (SubtitleParser.Segment seg : segments) {
            assertFalse(seg.getText().contains("<"), "有标签残留: " + seg.getText());
        }
    }
}
