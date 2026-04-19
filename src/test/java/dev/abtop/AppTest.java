package dev.abtop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for App utility methods — review findings #6, sanitization.
 */
class AppTest {

    @Test
    void sanitizeFallbackNormal() {
        assertEquals("hello world", App.sanitizeFallback("hello world", 28));
    }

    @Test
    void sanitizeFallbackTruncates() {
        assertEquals("hello…", App.sanitizeFallback("hello world", 5));
    }

    @Test
    void sanitizeFallbackNull() {
        assertEquals("—", App.sanitizeFallback(null, 28));
    }

    @Test
    void sanitizeFallbackEmpty() {
        assertEquals("—", App.sanitizeFallback("", 28));
    }

    @Test
    void sanitizeFallbackStripsControlChars() {
        // \u0000 is a control char, stripped entirely (no space inserted)
        assertEquals("helloworld", App.sanitizeFallback("hello\u0000world", 28));
    }

    @Test
    void sanitizeFallbackPreservesMultibyte() {
        // Should count codepoints, not bytes
        String input = "修复€100定价";  // 8 codepoints
        String result = App.sanitizeFallback(input, 5);
        assertEquals("修复€10…", result);
    }
}
