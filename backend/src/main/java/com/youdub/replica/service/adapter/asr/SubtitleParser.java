package com.youdub.replica.service.adapter.asr;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WebVTT 字幕文件解析器。
 * 将 YouTube 自动生成的 .vtt 字幕解析为 ASR 标准段列表。
 */
@Slf4j
public final class SubtitleParser {

    private SubtitleParser() {}

    /**
     * 解析 WebVTT 文件。
     *
     * @param vttFile .vtt 字幕文件路径
     * @return 段列表
     */
    public static List<Segment> parse(Path vttFile) throws IOException {
        List<Segment> segments = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(vttFile, StandardCharsets.UTF_8)) {
            String line;

            // skip WEBVTT header region until first blank line
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    break;
                }
            }

            while ((line = reader.readLine()) != null) {
                // 跳过空白行（纯空格行也跳过，它们在 YouTube VTT 中不是 cue 分隔符）
                if (line.trim().isEmpty()) {
                    continue;
                }

                // WebVTT cues start with a timestamp line containing -->
                if (!line.contains("-->")) {
                    continue;
                }

                // timestamp format: HH:MM:SS.mmm --> HH:MM:SS.mmm [optional cue settings]
                String[] parts = line.split("-->");
                long startTime = parseTimestamp(parts[0].trim());
                String endPart = parts[1].trim().split("\\s+")[0];
                long endTime = parseTimestamp(endPart);

                // collect text lines until truly blank line (not just space-only)
                StringBuilder textBuilder = new StringBuilder();
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        if (textBuilder.length() > 0) {
                            textBuilder.append(" ");
                        }
                        textBuilder.append(trimmed);
                    }
                }

                String text = stripVttTags(textBuilder.toString()).trim();
                if (!text.isEmpty()) {
                    segments.add(new Segment(text, startTime, endTime));
                }
            }
        }

        log.debug("解析 WebVTT 完成：{} 条原始字幕", segments.size());

        // 后处理：去掉回声段、合并重叠文本
        segments = postProcess(segments);

        log.debug("后处理完成：{} 条有效字幕", segments.size());
        return segments;
    }

    /**
     * 将 WebVTT 时间戳格式 (HH:MM:SS.mmm) 转换为毫秒。
     */
    private static long parseTimestamp(String ts) {
        int hh = Integer.parseInt(ts.substring(0, 2));
        int mm = Integer.parseInt(ts.substring(3, 5));
        int ss = Integer.parseInt(ts.substring(6, 8));
        int millis = Integer.parseInt(ts.substring(9, 12));
        return (long) hh * 3600000L + (long) mm * 60000L + (long) ss * 1000L + millis;
    }

    /**
     * 剥离 WebVTT 内联标签，只保留纯文本。
     * 如：<00:06:04.120><c> we</c> → " we"
     */
    private static String stripVttTags(String raw) {
        return raw.replaceAll("<[^>]+>", "");
    }

    // 回声段最大时长（毫秒）：低于此值的段若被下一段包含则视为回声
    private static final long ECHO_DURATION_THRESHOLD_MS = 100;

    /**
     * 后处理：去掉回声段，去除 continuation 段中的重叠文本前缀。
     * YouTube 自动字幕有 "单词级标记段 → 回声段 → 延续段" 的规律模式。
     */
    private static List<Segment> postProcess(List<Segment> raw) {
        if (raw.isEmpty()) return raw;

        List<Segment> cleaned = new ArrayList<>();

        // 第一遍：移除回声段（极短时长且文本被下一段包含）
        for (int i = 0; i < raw.size(); i++) {
            Segment cur = raw.get(i);
            long dur = cur.endTimeMs - cur.startTimeMs;

            if (dur < ECHO_DURATION_THRESHOLD_MS) {
                // 检查下一段是否包含本段文本（大小写不敏感）
                if (i + 1 < raw.size()) {
                    String nextText = raw.get(i + 1).getText().toLowerCase();
                    String curText = cur.getText().toLowerCase();
                    if (nextText.contains(curText)) {
                        continue; // 跳过回声段
                    }
                }
            }
            cleaned.add(cur);
        }

        // 第二遍：对 continuation 段去掉与前段重叠的文本前缀
        List<Segment> result = new ArrayList<>();
        result.add(cleaned.get(0));
        for (int i = 1; i < cleaned.size(); i++) {
            Segment cur = cleaned.get(i);
            Segment prev = result.get(result.size() - 1);  // 用 result 中已裁剪的末项，而非 cleaned 中的原始段
            String curText = cur.getText();
            String prevText = prev.getText();

            if (curText.length() > prevText.length()
                    && curText.regionMatches(true, 0, prevText, 0, prevText.length())) {
                String newText = curText.substring(prevText.length()).trim();
                if (!newText.isEmpty()) {
                    result.add(new Segment(newText, cur.startTimeMs, cur.endTimeMs));
                }
            } else {
                result.add(cur);
            }
        }

        return result;
    }

    /**
     * 字幕段，包含文本和时间戳。
     */
    public static class Segment {
        private final String text;
        private final long startTimeMs;
        private final long endTimeMs;

        public Segment(String text, long startTimeMs, long endTimeMs) {
            this.text = text;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
        }

        public String getText() {
            return text;
        }

        public long getStartTimeMs() {
            return startTimeMs;
        }

        public long getEndTimeMs() {
            return endTimeMs;
        }
    }
}
