package dev.abtop.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodexCollector incremental JSONL parsing.
 * Currently CodexCollector re-reads the entire file each tick.
 * After fix: should track offset and only parse new bytes.
 */
class CodexIncrementalParseTest {

    @TempDir
    Path tempDir;

    private static final String SESSION_META =
            "{\"type\":\"session_meta\",\"timestamp\":\"2026-04-19T10:00:00Z\"," +
            "\"payload\":{\"id\":\"abc-123\",\"cwd\":\"/tmp/test\",\"cli_version\":\"0.1.5\"," +
            "\"timestamp\":\"2026-04-19T10:00:00Z\",\"git\":{\"branch\":\"main\"}}}";

    private static final String TOKEN_COUNT_1 =
            "{\"type\":\"event_msg\",\"timestamp\":\"2026-04-19T10:01:00Z\"," +
            "\"payload\":{\"type\":\"token_count\",\"info\":{" +
            "\"total_token_usage\":{\"input_tokens\":500,\"output_tokens\":100,\"cached_input_tokens\":50}," +
            "\"last_token_usage\":{\"input_tokens\":200,\"output_tokens\":40,\"cached_input_tokens\":20}}}}";

    private static final String TOKEN_COUNT_2 =
            "{\"type\":\"event_msg\",\"timestamp\":\"2026-04-19T10:02:00Z\"," +
            "\"payload\":{\"type\":\"token_count\",\"info\":{" +
            "\"total_token_usage\":{\"input_tokens\":1200,\"output_tokens\":300,\"cached_input_tokens\":100}," +
            "\"last_token_usage\":{\"input_tokens\":400,\"output_tokens\":80,\"cached_input_tokens\":30}}}}";

    @Test
    void fullParseThenIncremental() throws Exception {
        Path file = tempDir.resolve("rollout.jsonl");

        // Write initial content
        Files.writeString(file,
                SESSION_META + "\n" + TOKEN_COUNT_1 + "\n",
                StandardCharsets.UTF_8);

        // First parse: full scan
        var result1 = CodexCollector.parseCodexJsonl(file, 0);
        assertNotNull(result1);
        assertEquals("abc-123", result1.sessionId);
        assertEquals(500, result1.totalInput);
        assertEquals(100, result1.totalOutput);
        assertTrue(result1.newOffset > 0, "Offset should advance");

        // Append more data
        Files.writeString(file, TOKEN_COUNT_2 + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        // Incremental parse from previous offset
        var result2 = CodexCollector.parseCodexJsonl(file, result1.newOffset);
        assertNotNull(result2);
        // Codex token_count has cumulative totals
        assertEquals(1200, result2.totalInput, "Delta should have cumulative tokens from new event");

        // Merge — for Codex, merge takes latest cumulative value
        result1.mergeFrom(result2);
        assertEquals(1200, result1.totalInput, "After merge: latest cumulative value = 1200");
        assertEquals(300, result1.totalOutput, "After merge: latest cumulative value = 300");
    }

    @Test
    void noNewDataReturnsEmptyDelta() throws Exception {
        Path file = tempDir.resolve("rollout2.jsonl");
        Files.writeString(file, SESSION_META + "\n" + TOKEN_COUNT_1 + "\n", StandardCharsets.UTF_8);

        var full = CodexCollector.parseCodexJsonl(file, 0);
        long offset = full.newOffset;

        var empty = CodexCollector.parseCodexJsonl(file, offset);
        assertNotNull(empty);
        assertEquals(0, empty.totalInput, "No new tokens when file unchanged");
    }
}
