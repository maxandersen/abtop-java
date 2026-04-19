package dev.abtop.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentSessionTest {

    private AgentSession make(long input, long output, long cacheRead, long cacheCreate) {
        var s = new AgentSession("claude", 0);
        s.setTotalInputTokens(input);
        s.setTotalOutputTokens(output);
        s.setTotalCacheRead(cacheRead);
        s.setTotalCacheCreate(cacheCreate);
        return s;
    }

    @Test
    void totalTokens() {
        var s = make(100, 50, 200, 30);
        assertEquals(380, s.totalTokens()); // 100 + 50 + 200 + 30
    }

    @Test
    void activeTokens() {
        var s = make(100, 50, 200, 30);
        assertEquals(180, s.activeTokens()); // 100 + 50 + 30, excludes cacheRead
    }
}
