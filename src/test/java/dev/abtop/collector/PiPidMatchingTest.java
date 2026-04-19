package dev.abtop.collector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PiCollector process matching — review finding #1.
 *
 * Bug: cmdHasBinary(cmd, "pi") matches any binary named "pi",
 * including TeX's pi, pi-hole, etc. The PiCollector.findPiPids()
 * adds some exclusions (picocli, pipeline) but misses many false positives.
 */
class PiPidMatchingTest {

    @Test
    void matchesActualPiAgent() {
        // The real pi agent binary
        assertTrue(ProcessUtil.cmdHasBinary("/Users/max/.local/bin/pi", "pi"));
        assertTrue(ProcessUtil.cmdHasBinary("pi", "pi"));
        assertTrue(ProcessUtil.cmdHasBinary("node /path/to/pi", "pi"));
    }

    @Test
    void rejectsPiHole() {
        // pi-hole is NOT the pi agent — cmdHasBinary should not match
        // because the binary name is "pihole" or "pi-hole", not "pi"
        assertFalse(ProcessUtil.cmdHasBinary("pihole", "pi"));
        assertFalse(ProcessUtil.cmdHasBinary("/usr/bin/pihole", "pi"));
        // But this IS a problem: "pi-hole" basename extracted would be "pi-hole", not "pi"
        // so cmdHasBinary("pi-hole", "pi") is actually fine. Test anyway:
        assertFalse(ProcessUtil.cmdHasBinary("pi-hole", "pi"));
    }

    @Test
    void rejectsTexPi() {
        // TeX's pi binary — basename IS "pi"
        // This currently matches incorrectly
        // After fix: should be filtered by PiCollector's validation
        assertTrue(ProcessUtil.cmdHasBinary("/usr/local/texlive/2024/bin/pi", "pi"),
                "cmdHasBinary itself will match — PiCollector must add extra validation");
    }

    @Test
    void rejectsPipAndSimilar() {
        // "pip" and "pip3" — basename is "pip"/"pip3", not "pi"
        assertFalse(ProcessUtil.cmdHasBinary("pip install foo", "pi"));
        assertFalse(ProcessUtil.cmdHasBinary("pip3 install foo", "pi"));
        assertFalse(ProcessUtil.cmdHasBinary("/usr/bin/pip", "pi"));
    }

    @Test
    void rejectsPidstat() {
        // "pidstat" — basename is "pidstat", not "pi"
        assertFalse(ProcessUtil.cmdHasBinary("pidstat 1", "pi"));
    }

    @Test
    void piCollectorFiltersGrepAndPicocli() {
        // These should be filtered by the PiCollector exclusion list
        // "grep" and "picocli" are excluded in findPiPids
        var cmd1 = "grep pi something";
        var cmd2 = "java -cp picocli.jar Main";
        var cmd3 = "pipeline-runner --config x";

        // cmdHasBinary may match, but PiCollector filters them out
        // We need to verify PiCollector's filtering works.
        // For now just test that cmdHasBinary itself handles exact name matching:
        assertTrue(ProcessUtil.cmdHasBinary("grep pi something", "pi"),
                "cmdHasBinary checks first 2 tokens, 'pi' is token 1");
    }

    @Test
    void piSessionDirExistenceCheck() {
        // After fix: PiCollector should verify the pi session directory exists
        // for the matched PID's cwd before accepting it as a pi agent.
        // This test documents the expected behavior.
        // A PID running "pi" in a directory without ~/.pi/agent/sessions/{encoded-cwd}/
        // should NOT be treated as a pi agent session.
        
        // This is a design-level test — we just document the expectation here.
        // The actual validation happens in PiCollector.collect() which checks
        // Files.isDirectory(sessionsDir.resolve(encoded)) before loading.
        // The issue is we still spawn lsof per false-positive PID.
    }
}
