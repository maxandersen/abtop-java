package dev.abtop.collector;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProcessUtil — review findings #4, general correctness.
 */
class ProcessUtilTest {

    // --- cmdHasBinary ---

    @Test
    void cmdHasBinaryExactMatch() {
        assertTrue(ProcessUtil.cmdHasBinary("claude", "claude"));
        assertTrue(ProcessUtil.cmdHasBinary("/usr/local/bin/claude", "claude"));
        assertTrue(ProcessUtil.cmdHasBinary("node /path/to/claude", "claude"));
    }

    @Test
    void cmdHasBinaryNoMatch() {
        assertFalse(ProcessUtil.cmdHasBinary("claud", "claude"));
        assertFalse(ProcessUtil.cmdHasBinary("claudette run", "claude"));
        assertFalse(ProcessUtil.cmdHasBinary("some-claude-wrapper foo", "claude"));
    }

    @Test
    void cmdHasBinaryEmptyInputs() {
        assertFalse(ProcessUtil.cmdHasBinary("", "claude"));
        assertFalse(ProcessUtil.cmdHasBinary("claude", ""));
        assertFalse(ProcessUtil.cmdHasBinary("", ""));
    }

    @Test
    void cmdHasBinaryOnlyChecksTwoTokens() {
        // Third token should NOT be checked
        assertFalse(ProcessUtil.cmdHasBinary("node script.js claude", "claude"));
        assertTrue(ProcessUtil.cmdHasBinary("node claude --flag", "claude"));
    }

    // --- isDescendantOf takes process map (#4 perf fix) ---

    @Test
    void isDescendantOfWithProvidedMap() {
        // Build a fake process tree: 1 -> 10 -> 100 -> 1000
        var procs = new HashMap<Integer, ProcessUtil.ProcInfo>();
        procs.put(1, new ProcessUtil.ProcInfo(1, 0, 100, 0.0, "init"));
        procs.put(10, new ProcessUtil.ProcInfo(10, 1, 100, 0.0, "shell"));
        procs.put(100, new ProcessUtil.ProcInfo(100, 10, 100, 0.0, "claude"));
        procs.put(1000, new ProcessUtil.ProcInfo(1000, 100, 100, 0.0, "cargo"));

        // 1000 is descendant of 10
        assertTrue(ProcessUtil.isDescendantOf(1000, 10, procs));
        // 1000 is descendant of 1
        assertTrue(ProcessUtil.isDescendantOf(1000, 1, procs));
        // 10 is NOT descendant of 100
        assertFalse(ProcessUtil.isDescendantOf(10, 100, procs));
        // Same PID
        assertTrue(ProcessUtil.isDescendantOf(100, 100, procs));
        // Non-existent PID
        assertFalse(ProcessUtil.isDescendantOf(9999, 1, procs));
    }

    // --- allDescendants ---

    @Test
    void allDescendantsDeepTree() {
        var childrenMap = Map.of(
                1, List.of(10, 11),
                10, List.of(100),
                100, List.of(1000)
        );
        var descendants = ProcessUtil.allDescendants(1, childrenMap);
        assertTrue(descendants.contains(10));
        assertTrue(descendants.contains(11));
        assertTrue(descendants.contains(100));
        assertTrue(descendants.contains(1000));
        assertEquals(4, descendants.size());
    }

    @Test
    void allDescendantsNoChildren() {
        Map<Integer, List<Integer>> childrenMap = Map.of();
        var descendants = ProcessUtil.allDescendants(999, childrenMap);
        assertTrue(descendants.isEmpty());
    }

    @Test
    void allDescendantsCycleProtection() {
        // Circular: 1 -> 2 -> 3 -> 1 (shouldn't happen in real ps, but be safe)
        var childrenMap = Map.of(
                1, List.of(2),
                2, List.of(3),
                3, List.of(1)
        );
        var descendants = ProcessUtil.allDescendants(1, childrenMap);
        // Should terminate, visited set prevents infinite loop
        assertEquals(3, descendants.size());
    }

    // --- hasActiveDescendant ---

    @Test
    void hasActiveDescendantFindsDeep() {
        var procs = new HashMap<Integer, ProcessUtil.ProcInfo>();
        procs.put(1, new ProcessUtil.ProcInfo(1, 0, 100, 0.0, "shell"));
        procs.put(10, new ProcessUtil.ProcInfo(10, 1, 100, 0.0, "claude"));
        procs.put(100, new ProcessUtil.ProcInfo(100, 10, 100, 50.0, "cargo")); // high CPU

        var childrenMap = Map.of(
                1, List.of(10),
                10, List.of(100)
        );

        assertTrue(ProcessUtil.hasActiveDescendant(1, childrenMap, procs, 5.0));
    }

    @Test
    void hasActiveDescendantNoneActive() {
        var procs = new HashMap<Integer, ProcessUtil.ProcInfo>();
        procs.put(1, new ProcessUtil.ProcInfo(1, 0, 100, 0.0, "shell"));
        procs.put(10, new ProcessUtil.ProcInfo(10, 1, 100, 0.5, "claude"));

        var childrenMap = Map.of(1, List.of(10));

        assertFalse(ProcessUtil.hasActiveDescendant(1, childrenMap, procs, 5.0));
    }
}
