package com.youdub.replica.util;

/**
 * 文件名处理工具类。
 */
public final class FilenameUtils {

    private static final String ILLEGAL_CHARS_REGEX = "[\\\\/:*?\"<>|]";

    private FilenameUtils() {
    }

    /**
     * 替换文件名中不允许的字符为下划线。
     *
     * @param name 原始名称
     * @return 安全文件名，null 输入返回 "untitled"
     */
    public static String sanitize(String name) {
        return sanitize(name, false);
    }

    /**
     * 替换文件名中不允许的字符为下划线，可选 trim。
     *
     * @param name 原始名称
     * @param trim 是否 trim 结果
     * @return 安全文件名，null 或空输入返回 "untitled"
     */
    public static String sanitize(String name, boolean trim) {
        if (name == null || name.isBlank()) return "untitled";
        String result = name.replaceAll(ILLEGAL_CHARS_REGEX, "_");
        return trim ? result.trim() : result;
    }

    /**
     * 去掉文件扩展名： "我的视频.mp4" → "我的视频"
     *
     * @param filename 文件名
     * @return 去掉后缀后的文件名，null 返回 null
     */
    public static String stripExtension(String filename) {
        if (filename == null || filename.isBlank()) return filename;
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
