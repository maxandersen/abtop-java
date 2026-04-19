package dev.abtop.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeTranscriptParserTest {

    @TempDir Path tempDir;

    private Path writeLines(String... lines) throws IOException {
        Path file = tempDir.resolve("test.jsonl");
        Files.write(file, List.of(lines));
        return file;
    }

    @Test
    void parseBasicTokens() throws IOException {
        var file = writeLines(
                """
                {"type":"user","timestamp":"2026-03-28T15:00:00Z","version":"2.1.86","gitBranch":"main","message":{"role":"user","content":"fix the bug"}}""",
                """
                {"type":"assistant","timestamp":"2026-03-28T15:00:05Z","message":{"role":"assistant","model":"claude-sonnet-4-6","usage":{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":200,"cache_creation_input_tokens":30},"content":[{"type":"text","text":"I found the issue."}]}}"""
        );
        var result = ClaudeCollector.parseTranscript(file, 0);
        assertEquals(100, result.totalInput);
        assertEquals(50, result.totalOutput);
        assertEquals(200, result.totalCacheRead);
        assertEquals(30, result.totalCacheCreate);
        assertEquals("claude-sonnet-4-6", result.model);
        assertEquals(1, result.turnCount);
        assertEquals(330, result.lastContextTokens); // 100 + 200 + 30
    }

    @Test
    void parseMultipleTurns() throws IOException {
        var file = writeLines(
                """
                {"type":"user","timestamp":"2026-03-28T15:00:00Z","message":{"role":"user","content":"first prompt"}}""",
                """
                {"type":"assistant","timestamp":"2026-03-28T15:00:05Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"First response."}]}}""",
                """
                {"type":"user","timestamp":"2026-03-28T15:01:00Z","message":{"role":"user","content":"second prompt"}}""",
                """
                {"type":"assistant","timestamp":"2026-03-28T15:01:05Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":200,"output_tokens":80,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"Second response."}]}}"""
        );
        var result = ClaudeCollector.parseTranscript(file, 0);
        assertEquals(2, result.turnCount);
        assertEquals(300, result.totalInput);
        assertEquals(130, result.totalOutput);
        assertEquals(2, result.tokenHistory.size());
    }

    @Test
    void parseToolUseCurrentTask() throws IOException {
        var file = writeLines(
                """
                {"type":"user","timestamp":"2026-03-28T15:00:00Z","message":{"role":"user","content":"fix the bug"}}""",
                """
                {"type":"assistant","timestamp":"2026-03-28T15:00:05Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"tool_use","name":"Edit","input":{"file_path":"src/main.rs"}}]}}"""
        );
        var result = ClaudeCollector.parseTranscript(file, 0);
        assertEquals("Edit src/main.rs", result.currentTask);
    }

    @Test
    void parseInitialPrompt() throws IOException {
        var file = writeLines(
                """
                {"type":"user","timestamp":"2026-03-28T15:00:00Z","message":{"role":"user","content":"refactor the auth module"}}"""
        );
        var result = ClaudeCollector.parseTranscript(file, 0);
        assertEquals("refactor the auth module", result.initialPrompt);
    }

    @Test
    void parseIncrementalOffset() throws IOException {
        var file = writeLines(
                """
                {"type":"user","timestamp":"2026-03-28T15:00:00Z","message":{"role":"user","content":"first prompt"}}""",
                """
                {"type":"assistant","timestamp":"2026-03-28T15:00:05Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"First response."}]}}"""
        );
        var first = ClaudeCollector.parseTranscript(file, 0);
        long offset = first.newOffset;
        assertTrue(offset > 0);

        // Append
        Files.writeString(file, "\n" + """
                {"type":"assistant","timestamp":"2026-03-28T15:01:05Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":40,"output_tokens":20,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"Third."}]}}""",
                java.nio.file.StandardOpenOption.APPEND);

        var delta = ClaudeCollector.parseTranscript(file, offset);
        assertEquals(1, delta.turnCount);
        assertEquals(40, delta.totalInput);
        assertEquals(20, delta.totalOutput);
    }

    @Test
    void parseEmptyFile() throws IOException {
        var file = tempDir.resolve("empty.jsonl");
        Files.createFile(file);
        var result = ClaudeCollector.parseTranscript(file, 0);
        assertEquals("-", result.model);
        assertEquals(0, result.totalInput);
        assertEquals(0, result.turnCount);
    }

    @Test
    void parseSkipsMalformedJson() throws IOException {
        var file = writeLines(
                """
                {"type":"user","timestamp":"2026-03-28T15:00:00Z","message":{"role":"user","content":"hi"}}""",
                "THIS IS NOT VALID JSON",
                """
                {"type":"assistant","timestamp":"2026-03-28T15:00:05Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"response"}]}}"""
        );
        var result = ClaudeCollector.parseTranscript(file, 0);
        assertEquals(1, result.turnCount);
        assertEquals(100, result.totalInput);
        assertEquals("hi", result.initialPrompt);
    }

    @Test
    void parseFileShrunkResets() throws IOException {
        var file = writeLines(
                """
                {"type":"user","timestamp":"2026-03-28T15:00:00Z","message":{"role":"user","content":"first"}}""",
                """
                {"type":"assistant","timestamp":"2026-03-28T15:00:05Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"resp"}]}}"""
        );
        var first = ClaudeCollector.parseTranscript(file, 0);
        long oldOffset = first.newOffset;
        assertTrue(oldOffset > 0);

        // Truncate and write shorter content
        Files.writeString(file, """
                {"type":"assistant","timestamp":"2026-03-28T16:00:00Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"new session"}]}}
                """);

        var result = ClaudeCollector.parseTranscript(file, oldOffset);
        assertEquals(1, result.turnCount);
        assertEquals(10, result.totalInput);
    }

    @Test
    void parseCurrentTaskClearedBetweenTurns() throws IOException {
        var file = writeLines(
                """
                {"type":"assistant","timestamp":"2026-03-28T15:00:05Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"tool_use","name":"Edit","input":{"file_path":"src/main.rs"}}]}}""",
                """
                {"type":"assistant","timestamp":"2026-03-28T15:01:05Z","message":{"model":"claude-sonnet-4-6","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"Done, all changes applied."}]}}"""
        );
        var result = ClaudeCollector.parseTranscript(file, 0);
        assertEquals(2, result.turnCount);
        assertEquals("", result.currentTask);
    }

    @Test
    void parseVersionAndGitBranch() throws IOException {
        var file = writeLines(
                """
                {"type":"user","timestamp":"2026-03-28T15:00:00Z","version":"2.1.90","gitBranch":"feat/payments","message":{"role":"user","content":"add stripe"}}"""
        );
        var result = ClaudeCollector.parseTranscript(file, 0);
        assertEquals("2.1.90", result.version);
        assertEquals("feat/payments", result.gitBranch);
    }

    @Test
    void encodeCwdPath() {
        assertEquals("-Users-foo-bar", ClaudeCollector.encodeCwdPath("/Users/foo/bar"));
        assertEquals("-home-user-my-project-v2", ClaudeCollector.encodeCwdPath("/home/user/my_project.v2"));
    }

    @Test
    void contextWindowForModel() {
        assertEquals(200_000, ClaudeCollector.contextWindowForModel("claude-opus-4-6", 50_000));
        assertEquals(1_000_000, ClaudeCollector.contextWindowForModel("claude-opus-4-6[1m]", 0));
        assertEquals(200_000, ClaudeCollector.contextWindowForModel("claude-sonnet-4-6", 100_000));
        assertEquals(200_000, ClaudeCollector.contextWindowForModel("unknown-model", 0));
        assertEquals(1_000_000, ClaudeCollector.contextWindowForModel("claude-opus-4-6", 250_000));
    }

    @Test
    void truncateString() {
        assertEquals("hell…", ClaudeCollector.truncate("hello world", 5));
        assertEquals("hi", ClaudeCollector.truncate("hi", 5));
    }

    @Test
    void shortenPath() {
        assertEquals("collector/claude.rs", ClaudeCollector.shortenPath("src/collector/claude.rs"));
        assertEquals("main.rs", ClaudeCollector.shortenPath("main.rs"));
    }

    @Test
    void readEffortFromSettings() throws IOException {
        var file = tempDir.resolve("settings.json");
        Files.writeString(file, """
                {"effortLevel":"high","other":true}""");
        assertEquals("high", ClaudeCollector.readEffortFromSettings(file));
    }

    @Test
    void readEffortMissingFieldReturnsNull() throws IOException {
        var file = tempDir.resolve("settings.json");
        Files.writeString(file, """
                {"permissions":{"deny":[]}}""");
        assertNull(ClaudeCollector.readEffortFromSettings(file));
    }

    @Test
    void readEffortEmptyStringReturnsNull() throws IOException {
        var file = tempDir.resolve("settings.json");
        Files.writeString(file, """
                {"effortLevel":""}""");
        assertNull(ClaudeCollector.readEffortFromSettings(file));
    }
}
