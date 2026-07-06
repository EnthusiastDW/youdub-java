package com.youdub.replica.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FilenameUtilsTest {

    // ==================== sanitize(String) ====================

    @Test
    void sanitize_nullReturnsUntitled() {
        assertEquals("untitled", FilenameUtils.sanitize(null));
    }

    @Test
    void sanitize_blankReturnsUntitled() {
        assertEquals("untitled", FilenameUtils.sanitize("  "));
    }

    @Test
    void sanitize_normalTextUnchanged() {
        assertEquals("hello", FilenameUtils.sanitize("hello"));
    }

    @Test
    void sanitize_replacesIllegalCharsWithUnderscore() {
        assertEquals("a_b_c_d_e_f_g", FilenameUtils.sanitize("a\\b:c*d?e\"f<g"));
    }

    @Test
    void sanitize_replacesPipeSymbol() {
        assertEquals("a_b", FilenameUtils.sanitize("a|b"));
    }

    @Test
    void sanitize_chineseCharsKept() {
        assertEquals("我的视频", FilenameUtils.sanitize("我的视频"));
    }

    // ==================== sanitize(String, boolean) ====================

    @Test
    void sanitize_trimFalse_keepsTrailingSpace() {
        assertEquals("hello ", FilenameUtils.sanitize("hello ", false));
    }

    @Test
    void sanitize_trimTrue_removesTrailingSpace() {
        assertEquals("hello", FilenameUtils.sanitize("hello ", true));
    }

    @Test
    void sanitize_trimTrue_removesLeadingSpace() {
        assertEquals("hello", FilenameUtils.sanitize("  hello", true));
    }

    @Test
    void sanitize_trimTrue_removesBothSides() {
        assertEquals("a_b", FilenameUtils.sanitize("  a|b  ", true));
    }

    // ==================== stripExtension ====================

    @Test
    void stripExtension_normalFile() {
        assertEquals("我的视频", FilenameUtils.stripExtension("我的视频.mp4"));
    }

    @Test
    void stripExtension_multipleDots_removesLast() {
        assertEquals("archive.tar", FilenameUtils.stripExtension("archive.tar.gz"));
    }

    @Test
    void stripExtension_noExtension_unchanged() {
        assertEquals("README", FilenameUtils.stripExtension("README"));
    }

    @Test
    void stripExtension_dotfile_unchanged() {
        assertEquals(".gitignore", FilenameUtils.stripExtension(".gitignore"));
    }

    @Test
    void stripExtension_nullReturnsNull() {
        assertNull(FilenameUtils.stripExtension(null));
    }

    @Test
    void stripExtension_emptyReturnsEmpty() {
        assertEquals("", FilenameUtils.stripExtension(""));
    }

    @Test
    void stripExtension_blankReturnsBlank() {
        assertEquals("  ", FilenameUtils.stripExtension("  "));
    }
}
