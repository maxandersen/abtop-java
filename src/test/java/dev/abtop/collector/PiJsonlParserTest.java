package dev.abtop.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PiJsonlParserTest {

    @TempDir Path tempDir;

    private static final String SESSION = """
            {"type":"session","version":3,"id":"abc-123","timestamp":"2026-04-18T21:00:00.000Z","cwd":"/Users/max/project"}""";

    private static final String MODEL_CHANGE = """
            {"type":"model_change","id":"m1","timestamp":"2026-04-18T21:00:01.000Z","provider":"vertex-anthropic","modelId":"claude-opus-4-6"}""";

    private Path writeLines(String... lines) throws IOException {
        Path file = tempDir.resolve("session.jsonl");
        Files.write(file, List.of(lines));
        return file;
    }

    @Test
    void parseSessionMeta() throws IOException {
        var file = writeLines(SESSION, MODEL_CHANGE);
        var result = PiCollector.parsePiJsonl(file);
        assertNotNull(result);
        assertEquals("abc-123", result.sessionId);
        assertEquals("/Users/max/project", result.cwd);
        assertEquals("claude-opus-4-6", result.model);
        assertEquals("vertex-anthropic", result.provider);
    }

    @Test
    void parseMessageWithUsage() throws IOException {
        var file = writeLines(
                SESSION, MODEL_CHANGE,
                """
                {"type":"message","id":"m2","timestamp":"2026-04-18T21:01:00.000Z","message":{"role":"user","content":[{"type":"text","text":"fix the bug"}]}}""",
                """
                {"type":"message","id":"m3","timestamp":"2026-04-18T21:01:05.000Z","message":{"role":"assistant","content":[{"type":"text","text":"I found it."}],"usage":{"input":100,"output":50,"cacheRead":200,"cacheWrite":30,"totalTokens":380}}}"""
        );
        var result = PiCollector.parsePiJsonl(file);
        assertNotNull(result);
        assertEquals(100, result.totalInput);
        assertEquals(50, result.totalOutput);
        assertEquals(200, result.totalCacheRead);
        assertEquals(30, result.totalCacheWrite);
        assertEquals(1, result.turnCount);
        assertEquals("fix the bug", result.initialPrompt);
        assertEquals("I found it.", result.firstAssistantText);
    }

    @Test
    void parseToolCall() throws IOException {
        var file = writeLines(
                SESSION, MODEL_CHANGE,
                """
                {"type":"message","id":"m3","timestamp":"2026-04-18T21:01:05.000Z","message":{"role":"assistant","content":[{"type":"toolCall","name":"edit","arguments":{"path":"src/main.rs"}}],"usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0,"totalTokens":15}}}"""
        );
        var result = PiCollector.parsePiJsonl(file);
        assertNotNull(result);
        assertEquals("edit main.rs", result.currentTask);
    }

    @Test
    void parseBashToolCall() throws IOException {
        var file = writeLines(
                SESSION, MODEL_CHANGE,
                """
                {"type":"message","id":"m3","timestamp":"2026-04-18T21:01:05.000Z","message":{"role":"assistant","content":[{"type":"toolCall","name":"bash","arguments":{"command":"cargo build"}}],"usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0,"totalTokens":15}}}"""
        );
        var result = PiCollector.parsePiJsonl(file);
        assertNotNull(result);
        assertEquals("bash cargo build", result.currentTask);
    }

    @Test
    void parseThinkingLevelChange() throws IOException {
        var file = writeLines(
                SESSION, MODEL_CHANGE,
                """
                {"type":"thinking_level_change","id":"t1","timestamp":"2026-04-18T21:00:02.000Z","level":"high"}"""
        );
        var result = PiCollector.parsePiJsonl(file);
        assertNotNull(result);
        assertEquals("high", result.thinkingLevel);
    }

    @Test
    void parseMultipleTurns() throws IOException {
        var file = writeLines(
                SESSION, MODEL_CHANGE,
                """
                {"type":"message","id":"m1","timestamp":"2026-04-18T21:01:00.000Z","message":{"role":"assistant","content":[{"type":"text","text":"First."}],"usage":{"input":50,"output":25,"cacheRead":0,"cacheWrite":0,"totalTokens":75}}}""",
                """
                {"type":"message","id":"m2","timestamp":"2026-04-18T21:02:00.000Z","message":{"role":"assistant","content":[{"type":"text","text":"Second."}],"usage":{"input":60,"output":30,"cacheRead":10,"cacheWrite":5,"totalTokens":105}}}"""
        );
        var result = PiCollector.parsePiJsonl(file);
        assertNotNull(result);
        assertEquals(2, result.turnCount);
        assertEquals(110, result.totalInput);
        assertEquals(55, result.totalOutput);
        assertEquals(10, result.totalCacheRead);
        assertEquals(5, result.totalCacheWrite);
        assertEquals(2, result.tokenHistory.size());
    }

    @Test
    void emptyFileReturnsNull() throws IOException {
        var file = tempDir.resolve("empty.jsonl");
        Files.createFile(file);
        assertNull(PiCollector.parsePiJsonl(file));
    }

    @Test
    void encodeCwd() {
        assertEquals("--Users-max-code--", PiCollector.encodeCwd("/Users/max/code"));
        assertEquals("--home-user-project--", PiCollector.encodeCwd("/home/user/project"));
    }

    @Test
    void skipsMalformedLines() throws IOException {
        var file = writeLines(
                SESSION,
                "NOT VALID JSON",
                """
                {"type":"message","id":"m1","timestamp":"2026-04-18T21:01:00.000Z","message":{"role":"assistant","content":[{"type":"text","text":"ok"}],"usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0,"totalTokens":15}}}"""
        );
        var result = PiCollector.parsePiJsonl(file);
        assertNotNull(result);
        assertEquals(1, result.turnCount);
    }

    @Test
    void taskClearedBetweenTurns() throws IOException {
        var file = writeLines(
                SESSION, MODEL_CHANGE,
                """
                {"type":"message","id":"m1","timestamp":"2026-04-18T21:01:00.000Z","message":{"role":"assistant","content":[{"type":"toolCall","name":"edit","arguments":{"path":"src/main.rs"}}],"usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0,"totalTokens":15}}}""",
                """
                {"type":"message","id":"m2","timestamp":"2026-04-18T21:02:00.000Z","message":{"role":"assistant","content":[{"type":"text","text":"All done."}],"usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0,"totalTokens":15}}}"""
        );
        var result = PiCollector.parsePiJsonl(file);
        assertNotNull(result);
        assertEquals(2, result.turnCount);
        assertEquals("", result.currentTask); // cleared because last turn had no tool
    }
}
