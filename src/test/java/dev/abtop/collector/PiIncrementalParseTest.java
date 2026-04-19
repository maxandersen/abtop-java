package dev.abtop.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PiCollector incremental JSONL parsing.
 * Currently PiCollector re-reads the entire file each tick.
 * After fix: should track offset and only parse new bytes.
 */
class PiIncrementalParseTest {

    @TempDir
    Path tempDir;

    private static final String SESSION_LINE =
            "{\"type\":\"session\",\"id\":\"ses1\",\"cwd\":\"/tmp/test\",\"timestamp\":\"2026-04-19T10:00:00Z\"}";

    private static final String ASSISTANT_LINE_1 =
            "{\"type\":\"message\",\"timestamp\":\"2026-04-19T10:01:00Z\",\"message\":{\"role\":\"assistant\"," +
            "\"usage\":{\"input\":100,\"output\":50,\"cacheRead\":0,\"cacheWrite\":0,\"totalTokens\":150}," +
            "\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}}";

    private static final String ASSISTANT_LINE_2 =
            "{\"type\":\"message\",\"timestamp\":\"2026-04-19T10:02:00Z\",\"message\":{\"role\":\"assistant\"," +
            "\"usage\":{\"input\":200,\"output\":80,\"cacheRead\":50,\"cacheWrite\":0,\"totalTokens\":330}," +
            "\"content\":[{\"type\":\"text\",\"text\":\"Done\"},{\"type\":\"toolCall\",\"name\":\"Edit\",\"arguments\":{\"path\":\"foo.txt\"}}]}}";

    @Test
    void fullParseThenIncremental() throws Exception {
        Path file = tempDir.resolve("session.jsonl");

        // Write initial content
        Files.writeString(file, SESSION_LINE + "\n" + ASSISTANT_LINE_1 + "\n", StandardCharsets.UTF_8);

        // First parse: full scan
        var result1 = PiCollector.parsePiJsonl(file, 0);
        assertNotNull(result1);
        assertEquals("ses1", result1.sessionId);
        assertEquals(1, result1.turnCount);
        assertEquals(100, result1.totalInput);
        assertEquals(50, result1.totalOutput);
        assertTrue(result1.newOffset > 0, "Offset should advance after parsing");

        // Append more data
        Files.writeString(file, ASSISTANT_LINE_2 + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        // Second parse: incremental from previous offset
        var result2 = PiCollector.parsePiJsonl(file, result1.newOffset);
        assertNotNull(result2);
        // Delta should only contain the new turn
        assertEquals(1, result2.turnCount, "Incremental parse should find 1 new turn");
        assertEquals(200, result2.totalInput, "Should have input tokens from new turn only");
        assertEquals(80, result2.totalOutput);

        // Merge
        result1.mergeFrom(result2);
        assertEquals(2, result1.turnCount, "After merge: 2 total turns");
        assertEquals(300, result1.totalInput, "After merge: 100+200=300 input tokens");
        assertEquals(130, result1.totalOutput, "After merge: 50+80=130 output tokens");
        assertEquals("Edit foo.txt", result1.currentTask, "Current task from latest turn");
    }

    @Test
    void incrementalFromMiddleSkipsSessionLine() throws Exception {
        Path file = tempDir.resolve("session2.jsonl");
        Files.writeString(file, SESSION_LINE + "\n" + ASSISTANT_LINE_1 + "\n", StandardCharsets.UTF_8);

        // Parse full
        var full = PiCollector.parsePiJsonl(file, 0);
        long offset = full.newOffset;

        // Parse from offset where there's nothing new
        var empty = PiCollector.parsePiJsonl(file, offset);
        assertNotNull(empty);
        assertEquals(0, empty.turnCount, "No new turns when file hasn't changed");
        assertEquals(0, empty.totalInput, "No new tokens");
    }

    @Test
    void fileShrinkResetsOffset() throws Exception {
        Path file = tempDir.resolve("session3.jsonl");
        Files.writeString(file, SESSION_LINE + "\n" + ASSISTANT_LINE_1 + "\n" + ASSISTANT_LINE_2 + "\n",
                StandardCharsets.UTF_8);

        var full = PiCollector.parsePiJsonl(file, 0);
        long bigOffset = full.newOffset;

        // Simulate file shrink (session restart)
        Files.writeString(file, SESSION_LINE + "\n", StandardCharsets.UTF_8);

        // Parse with old (too-large) offset — should detect shrink and re-scan
        var after = PiCollector.parsePiJsonl(file, bigOffset);
        assertNotNull(after);
        assertEquals("ses1", after.sessionId, "Session ID should be re-read after shrink");
        assertEquals(0, after.turnCount, "No assistant turns in truncated file");
    }
}
