package dev.abtop.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UiUtil formatting, model shortening, and truncation.
 */
class UiUtilTest {

    // --- fmtTokens ---

    @Test
    void fmtTokensSmall() {
        assertEquals("0", UiUtil.fmtTokens(0));
        assertEquals("999", UiUtil.fmtTokens(999));
    }

    @Test
    void fmtTokensThousands() {
        assertEquals("1.0k", UiUtil.fmtTokens(1000));
        assertEquals("42.5k", UiUtil.fmtTokens(42500));
        assertEquals("999.9k", UiUtil.fmtTokens(999900));
    }

    @Test
    void fmtTokensMillions() {
        assertEquals("1.0M", UiUtil.fmtTokens(1_000_000));
        assertEquals("18.5M", UiUtil.fmtTokens(18_500_000));
    }

    // --- shortenModel ---

    @Test
    void shortenModelClaude() {
        assertEquals("opus4.6", UiUtil.shortenModel("claude-opus-4-6", false));
        assertEquals("sonnet4.6", UiUtil.shortenModel("claude-sonnet-4-6", false));
        assertEquals("haiku4.5", UiUtil.shortenModel("claude-haiku-4-5", false));
    }

    @Test
    void shortenModel1m() {
        assertEquals("opus4.6[1m]", UiUtil.shortenModel("claude-opus-4-6[1m]", true));
    }

    @Test
    void shortenModelUnknown() {
        // Non-Claude models should pass through without crashing
        assertEquals("big-pickle", UiUtil.shortenModel("big-pickle", false));
        assertEquals("-", UiUtil.shortenModel("-", false));
    }

    // --- truncateStr ---

    @Test
    void truncateStrShort() {
        assertEquals("hello", UiUtil.truncateStr("hello", 10));
    }

    @Test
    void truncateStrExact() {
        assertEquals("hello", UiUtil.truncateStr("hello", 5));
    }

    @Test
    void truncateStrLong() {
        assertEquals("hell…", UiUtil.truncateStr("hello world", 5));
    }

    @Test
    void truncateStrZeroMax() {
        assertEquals("", UiUtil.truncateStr("hello", 0));
    }

    @Test
    void truncateStrMultibyte() {
        // Should count codepoints, not bytes
        String input = "日本語テスト";  // 6 codepoints
        assertEquals("日本語テスト", UiUtil.truncateStr(input, 6));
        assertEquals("日本…", UiUtil.truncateStr(input, 3));
    }

    @Test
    void truncateStrEmoji() {
        String input = "🚀🎉✨💡";  // 4 codepoints (each is 1+ chars in Java)
        assertEquals("🚀🎉✨💡", UiUtil.truncateStr(input, 4));
        assertEquals("🚀🎉…", UiUtil.truncateStr(input, 3));
    }

    // --- fmtMemKb ---

    @Test
    void fmtMemKbSmall() {
        assertEquals("512K", UiUtil.fmtMemKb(512));
    }

    @Test
    void fmtMemKbMega() {
        assertEquals("256M", UiUtil.fmtMemKb(256 * 1024));
    }

    @Test
    void fmtMemKbGiga() {
        assertEquals("2.5G", UiUtil.fmtMemKb((long) (2.5 * 1024 * 1024)));
    }
}
