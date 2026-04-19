package dev.abtop.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClaudeCollector.readLineBounded() — review finding #3.
 *
 * Bug: readLineBounded casts raf.read() (a byte) to char, which breaks
 * on multi-byte UTF-8. A byte > 127 becomes the wrong character.
 * For example, '€' (0xE2 0x82 0xAC) would become three garbled chars.
 */
class ReadLineBoundedTest {

    @TempDir
    Path tempDir;

    @Test
    void asciiRoundtrip() throws Exception {
        // ASCII should work fine with current impl
        Path file = tempDir.resolve("ascii.jsonl");
        String line = "{\"type\":\"user\",\"text\":\"hello world\"}";
        Files.writeString(file, line + "\n");

        var result = ClaudeCollector.parseTranscript(file, 0);
        // Just verify it doesn't crash — ASCII is fine
        assertNotNull(result);
    }

    @Test
    void multiBytUtf8InTranscript() throws Exception {
        // This is the actual bug: multi-byte UTF-8 characters get corrupted
        // because readLineBounded reads bytes and casts to char
        Path file = tempDir.resolve("utf8.jsonl");

        // A transcript line containing multi-byte chars (€ = 3 bytes, 日本語 = 3 bytes each)
        String jsonLine = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Fix the €100 pricing for 日本語 users\"}}";
        Files.writeString(file, jsonLine + "\n", StandardCharsets.UTF_8);

        var result = ClaudeCollector.parseTranscript(file, 0);
        // The initial prompt should contain the multi-byte characters intact
        assertNotNull(result);
        assertTrue(result.initialPrompt.contains("€") || result.initialPrompt.contains("日"),
                "Multi-byte UTF-8 chars should be preserved, got: " + result.initialPrompt);
    }

    @Test
    void emojiInTranscript() throws Exception {
        // Emoji are 4-byte UTF-8 sequences
        Path file = tempDir.resolve("emoji.jsonl");
        String jsonLine = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Add 🚀 rocket feature\"}}";
        Files.writeString(file, jsonLine + "\n", StandardCharsets.UTF_8);

        var result = ClaudeCollector.parseTranscript(file, 0);
        assertNotNull(result);
        assertTrue(result.initialPrompt.contains("🚀"),
                "4-byte emoji should be preserved, got: " + result.initialPrompt);
    }

    @Test
    void mixedAsciiAndMultibyte() throws Exception {
        // Two lines: one ASCII, one with multibyte — verify both parse correctly
        Path file = tempDir.resolve("mixed.jsonl");
        var sb = new StringBuilder();
        sb.append("{\"type\":\"user\",\"version\":\"2.1.86\",\"message\":{\"role\":\"user\",\"content\":\"Première étape: résoudre le problème\"}}\n");
        sb.append("{\"type\":\"assistant\",\"message\":{\"model\":\"claude-opus-4-6\",\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0},\"content\":[{\"type\":\"text\",\"text\":\"Voilà, c'est résolu!\"}]}}\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        var result = ClaudeCollector.parseTranscript(file, 0);
        assertNotNull(result);
        assertEquals("2.1.86", result.version);
        assertTrue(result.initialPrompt.contains("Première") || result.initialPrompt.contains("étape"),
                "French accented chars should be preserved, got: " + result.initialPrompt);
        assertTrue(result.firstAssistantText.contains("résolu"),
                "Assistant text accented chars should be preserved, got: " + result.firstAssistantText);
    }
}
