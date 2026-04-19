package dev.abtop.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodexJsonlParserTest {

    @TempDir Path tempDir;

    private static final String SESSION_META = """
            {"type":"session_meta","timestamp":"2026-03-28T15:00:00Z","payload":{"id":"sess-123","cwd":"/home/user/project","cli_version":"0.1.5","timestamp":"2026-03-28T15:00:00Z","git":{"branch":"feature/x"}}}""";

    private Path writeLines(String... lines) throws IOException {
        Path file = tempDir.resolve("rollout-test.jsonl");
        Files.write(file, List.of(lines));
        return file;
    }

    @Test
    void parseSessionMeta() throws IOException {
        var file = writeLines(SESSION_META);
        var result = CodexCollector.parseCodexJsonl(file);
        assertNotNull(result);
        assertEquals("sess-123", result.sessionId);
        assertEquals("/home/user/project", result.cwd);
        assertEquals("0.1.5", result.version);
        assertEquals("feature/x", result.gitBranch);
    }

    @Test
    void parseTokenCount() throws IOException {
        var file = writeLines(
                SESSION_META,
                """
                {"type":"event_msg","timestamp":"2026-03-28T15:01:00Z","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":500,"output_tokens":200,"cached_input_tokens":100},"last_token_usage":{"input_tokens":50,"output_tokens":20,"cached_input_tokens":10},"model_context_window":128000}}}"""
        );
        var result = CodexCollector.parseCodexJsonl(file);
        assertNotNull(result);
        assertEquals(500, result.totalInput);
        assertEquals(200, result.totalOutput);
        assertEquals(100, result.totalCacheRead);
        assertEquals(60, result.lastContextTokens); // 50 + 10
        assertEquals(128000, result.contextWindow);
        assertEquals(1, result.tokenHistory.size());
        assertEquals(80L, result.tokenHistory.getFirst()); // 50 + 20 + 10
    }

    @Test
    void parseRateLimits() throws IOException {
        var file = writeLines(
                SESSION_META,
                """
                {"type":"event_msg","timestamp":"2026-03-28T15:01:00Z","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":1,"output_tokens":1},"last_token_usage":{"input_tokens":1,"output_tokens":1}},"rate_limits":{"limit_id":"codex","primary":{"used_percent":9.0,"window_minutes":300,"resets_at":1774686045},"secondary":{"used_percent":14.0,"window_minutes":10080,"resets_at":1775186466},"plan_type":"plus"}}}"""
        );
        var result = CodexCollector.parseCodexJsonl(file);
        assertNotNull(result);
        assertNotNull(result.rateLimit);
        assertEquals(9.0, result.rateLimit.fiveHourPct());
        assertEquals(14.0, result.rateLimit.sevenDayPct());
    }

    @Test
    void parseCacheReadFallbackFieldName() throws IOException {
        var file = writeLines(
                SESSION_META,
                """
                {"type":"event_msg","timestamp":"2026-03-28T15:01:00Z","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":30},"last_token_usage":{"input_tokens":20,"output_tokens":10,"cache_read_input_tokens":5},"model_context_window":200000}}}"""
        );
        var result = CodexCollector.parseCodexJsonl(file);
        assertNotNull(result);
        assertEquals(30, result.totalCacheRead);
        assertEquals(25, result.lastContextTokens); // 20 + 5
    }

    @Test
    void skipsMalformedLines() throws IOException {
        var file = writeLines(
                SESSION_META,
                "NOT VALID JSON AT ALL",
                """
                {"type":"event_msg","timestamp":"2026-03-28T15:01:00Z","payload":{"type":"agent_message"}}"""
        );
        var result = CodexCollector.parseCodexJsonl(file);
        assertNotNull(result);
        assertEquals(1, result.turnCount);
    }

    @Test
    void parseTurnContextEffort() throws IOException {
        var file = writeLines(
                SESSION_META,
                """
                {"type":"turn_context","timestamp":"2026-03-28T15:01:00Z","payload":{"cwd":"/home/user/project","model":"gpt-5-codex","effort":"low","summary":"auto"}}""",
                """
                {"type":"turn_context","timestamp":"2026-03-28T15:02:00Z","payload":{"cwd":"/home/user/project","model":"gpt-5-codex","effort":"high","summary":"auto"}}"""
        );
        var result = CodexCollector.parseCodexJsonl(file);
        assertNotNull(result);
        assertEquals("gpt-5-codex", result.model);
        assertEquals("high", result.effort); // latest wins
    }

    @Test
    void missingEffortIsEmpty() throws IOException {
        var file = writeLines(
                SESSION_META,
                """
                {"type":"turn_context","timestamp":"2026-03-28T15:01:00Z","payload":{"cwd":"/home/user/project","model":"gpt-5-codex"}}"""
        );
        var result = CodexCollector.parseCodexJsonl(file);
        assertNotNull(result);
        assertEquals("", result.effort);
    }

    @Test
    void emptyFileReturnsNull() throws IOException {
        var file = tempDir.resolve("empty.jsonl");
        Files.createFile(file);
        assertNull(CodexCollector.parseCodexJsonl(file));
    }
}
