package com.youdub.replica.service.adapter.asr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SubtitleParser} 的单元测试。
 */
class SubtitleParserTest {

    @TempDir
    Path tempDir;

    @Test
    void simpleVtt() throws IOException {
        Path vtt = tempDir.resolve("simple.vtt");
        Files.writeString(vtt, """
                WEBVTT
                
                00:00:01.000 --> 00:00:04.000
                Hello, welcome to my video.
                
                00:00:05.000 --> 00:00:08.500
                Today we will be discussing system design.
                """);

        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertEquals(2, segments.size());

        assertEquals("Hello, welcome to my video.", segments.get(0).getText());
        assertEquals(1000, segments.get(0).getStartTimeMs());
        assertEquals(4000, segments.get(0).getEndTimeMs());

        assertEquals("Today we will be discussing system design.", segments.get(1).getText());
        assertEquals(5000, segments.get(1).getStartTimeMs());
        assertEquals(8500, segments.get(1).getEndTimeMs());
    }

    @Test
    void stripCTags() throws IOException {
        Path vtt = tempDir.resolve("ctags.vtt");
        Files.writeString(vtt, """
                WEBVTT
                
                00:06:02.000 --> 00:06:08.000
                duplicate our function for every type or can<00:06:04.120><c> we</c><00:06:04.440><c> somehow</c><00:06:05.120><c> tell</c><00:06:05.400><c> the</c><00:06:05.560><c> compiler</c><00:06:06.360><c> that</c>
                """);

        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertEquals(1, segments.size());

        String text = segments.get(0).getText();
        // 所有 <c> 和 <00:...> 标签应被剥离
        assertFalse(text.contains("<c>"), "应剥离 <c> 标签，实际：" + text);
        assertFalse(text.contains("</c>"), "应剥离 </c> 标签，实际：" + text);
        assertFalse(text.contains("<00:"), "应剥离时间戳标签，实际：" + text);
        // 纯文本应正确
        assertEquals("duplicate our function for every type or can we somehow tell the compiler that", text);
    }

    @Test
    void stripTimestampTags() throws IOException {
        Path vtt = tempDir.resolve("ts.vtt");
        Files.writeString(vtt, """
                WEBVTT
                
                00:01:00.000 --> 00:01:05.000
                <00:01:00.000><c> First</c><00:01:01.500><c> word</c><00:01:03.000><c> timings</c>
                """);

        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertEquals(1, segments.size());
        assertEquals("First word timings", segments.get(0).getText());
    }

    @Test
    void cueSettings() throws IOException {
        Path vtt = tempDir.resolve("settings.vtt");
        Files.writeString(vtt, """
                WEBVTT
                
                00:00:01.000 --> 00:00:03.000 align:start position:50%
                This has cue settings.
                """);

        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertEquals(1, segments.size());
        assertEquals("This has cue settings.", segments.get(0).getText());
        assertEquals(1000, segments.get(0).getStartTimeMs());
        assertEquals(3000, segments.get(0).getEndTimeMs());
    }

    @Test
    void multiLineText() throws IOException {
        Path vtt = tempDir.resolve("multiline.vtt");
        Files.writeString(vtt, """
                WEBVTT
                
                00:00:10.000 --> 00:00:15.000
                First line of text
                second line
                third line
                """);

        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertEquals(1, segments.size());
        assertEquals("First line of text second line third line", segments.get(0).getText());
    }

    @Test
    void emptyTextSkipped() throws IOException {
        Path vtt = tempDir.resolve("empty.vtt");
        Files.writeString(vtt, """
                WEBVTT
                
                00:00:01.000 --> 00:00:02.000
                
                00:00:03.000 --> 00:00:04.000
                Actual text here.
                """);

        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertEquals(1, segments.size());
        assertEquals("Actual text here.", segments.get(0).getText());
    }

    @Test
    void noCues() throws IOException {
        Path vtt = tempDir.resolve("empty.vtt");
        Files.writeString(vtt, "WEBVTT\n\n");
        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertTrue(segments.isEmpty());
    }

    @Test
    void malformedTimestampLineSkips() throws IOException {
        Path vtt = tempDir.resolve("malformed.vtt");
        Files.writeString(vtt, """
                WEBVTT
                
                this is not a timestamp line
                
                00:00:05.000 --> 00:00:10.000
                Valid line.
                """);

        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertEquals(1, segments.size());
        assertEquals("Valid line.", segments.get(0).getText());
    }

    @Test
    void fileNotFound() {
        Path nonexistent = tempDir.resolve("nonexistent.vtt");
        assertThrows(IOException.class, () -> SubtitleParser.parse(nonexistent));
    }

    @Test
    void vttWithVoiceTag() throws IOException {
        Path vtt = tempDir.resolve("voice.vtt");
        Files.writeString(vtt, """
                WEBVTT
                
                00:00:02.000 --> 00:00:06.000
                <v Speaker1>This text has a voice tag.</v>
                """);

        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertEquals(1, segments.size());
        assertEquals("This text has a voice tag.", segments.get(0).getText());
    }

    @Test
    void multipleSegmentsWithTags() throws IOException {
        Path vtt = tempDir.resolve("multi.vtt");
        Files.writeString(vtt, """
                WEBVTT
                
                00:00:00.000 --> 00:00:03.000
                <c>First</c> segment.
                
                00:00:03.500 --> 00:00:06.000
                <c>Second</c> segment with<00:00:05.000><c> tags</c>
                
                00:00:06.500 --> 00:00:09.000
                Third segment<c> no tags</c> here.
                """);

        List<SubtitleParser.Segment> segments = SubtitleParser.parse(vtt);
        assertEquals(3, segments.size());

        assertEquals("First segment.", segments.get(0).getText());
        assertEquals("Second segment with tags", segments.get(1).getText());
        assertEquals("Third segment no tags here.", segments.get(2).getText());
        assertEquals(0, segments.get(0).getStartTimeMs());
        assertEquals(3000, segments.get(0).getEndTimeMs());
        assertEquals(3500, segments.get(1).getStartTimeMs());
        assertEquals(6000, segments.get(1).getEndTimeMs());
        assertEquals(6500, segments.get(2).getStartTimeMs());
        assertEquals(9000, segments.get(2).getEndTimeMs());
    }
}
