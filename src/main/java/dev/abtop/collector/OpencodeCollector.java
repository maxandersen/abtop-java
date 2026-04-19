package dev.abtop.collector;

import dev.abtop.model.*;

import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Collector for opencode AI coding agent sessions.
 *
 * Discovery:
 *   1. Find running `opencode` processes via shared ps data
 *   2. Query ~/.local/share/opencode/opencode.db (SQLite) for session data
 *   3. Match running PIDs to sessions by cwd
 *
 * Data model:
 *   - session table: id, directory, title, time_created, time_updated
 *   - message table: role, tokens (input/output/cache), modelID
 *   - part table: tool calls, text content
 */
public class OpencodeCollector implements AgentCollector {

    private final Path dbPath;

    public OpencodeCollector() {
        String home = System.getProperty("user.home", "");
        // XDG_DATA_HOME or default
        String xdgData = System.getenv("XDG_DATA_HOME");
        Path dataDir = xdgData != null ? Path.of(xdgData) : Path.of(home, ".local", "share");
        this.dbPath = dataDir.resolve("opencode").resolve("opencode.db");
    }

    @Override
    public List<AgentSession> collect(SharedProcessData shared) {
        if (!Files.exists(dbPath)) return List.of();

        // Find running opencode processes and their cwds
        var pidToCwd = findOpencodePids(shared);
        if (pidToCwd.isEmpty() && !hasRecentSessions()) return List.of();

        var sessions = new ArrayList<AgentSession>();

        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // Get recent sessions (updated in last 5 minutes, or matching a running PID)
            var recentSessions = queryRecentSessions(conn, 300);

            for (var row : recentSessions) {
                // Try to match to a running PID by cwd
                int matchedPid = 0;
                for (var entry : pidToCwd.entrySet()) {
                    if (row.directory.equals(entry.getValue())) {
                        matchedPid = entry.getKey();
                        break;
                    }
                }

                var procInfo = matchedPid > 0 ? shared.processInfo().get(matchedPid) : null;
                boolean pidAlive = procInfo != null;

                // Status
                SessionStatus status;
                if (!pidAlive) {
                    status = SessionStatus.DONE;
                } else {
                    long sinceUpdate = Duration.between(
                            Instant.ofEpochMilli(row.timeUpdated), Instant.now()).toSeconds();
                    if (sinceUpdate < 30) {
                        status = SessionStatus.WORKING;
                    } else {
                        boolean cpuActive = procInfo.cpuPct() > 1.0;
                        boolean hasActiveChild = ProcessUtil.hasActiveDescendant(
                                matchedPid, shared.childrenMap(), shared.processInfo(), 5.0);
                        status = (cpuActive || hasActiveChild) ? SessionStatus.WORKING : SessionStatus.WAITING;
                    }
                }

                // Token totals from messages
                var tokens = queryTokenTotals(conn, row.id);
                String model = queryModel(conn, row.id);

                // Current task from last tool part
                String currentTask = queryLastTool(conn, row.id);

                // Initial prompt
                String initialPrompt = queryInitialPrompt(conn, row.id);

                // First assistant text
                String firstAssistantText = queryFirstAssistantText(conn, row.id);

                List<String> currentTasks;
                if (!currentTask.isEmpty()) {
                    currentTasks = List.of(currentTask);
                } else if (!pidAlive) {
                    currentTasks = List.of("finished");
                } else if (status == SessionStatus.WAITING) {
                    currentTasks = List.of("waiting for input");
                } else {
                    currentTasks = List.of("thinking...");
                }

                String projectName = row.directory.contains("/")
                        ? row.directory.substring(row.directory.lastIndexOf('/') + 1) : "?";

                long contextWindow = 200_000; // default
                double contextPercent = tokens.lastContext > 0
                        ? (tokens.lastContext * 100.0 / contextWindow) : 0.0;

                long memMb = procInfo != null ? procInfo.rssKb() / 1024 : 0;
                var children = matchedPid > 0 ? buildChildren(matchedPid, shared) : List.<ChildProcess>of();

                var session = new AgentSession("opencode", Math.max(matchedPid, 0));
                session.setSessionId(row.id);
                session.setCwd(row.directory);
                session.setProjectName(projectName);
                session.setStartedAt(row.timeCreated);
                session.setStatus(status);
                session.setModel(model);
                session.setContextPercent(contextPercent);
                session.setTotalInputTokens(tokens.input);
                session.setTotalOutputTokens(tokens.output);
                session.setTotalCacheRead(tokens.cacheRead);
                session.setTotalCacheCreate(tokens.cacheWrite);
                session.setTurnCount(tokens.turnCount);
                session.setCurrentTasks(currentTasks);
                session.setMemMb(memMb);
                session.setChildren(children);
                session.setInitialPrompt(initialPrompt);
                session.setFirstAssistantText(firstAssistantText);
                sessions.add(session);
            }
        } catch (SQLException ignored) {}

        sessions.sort(Comparator.comparingLong(AgentSession::getStartedAt).reversed());
        return sessions;
    }

    // --- Process discovery ---

    private Map<Integer, String> findOpencodePids(SharedProcessData shared) {
        var pids = new java.util.ArrayList<Integer>();
        for (var entry : shared.processInfo().entrySet()) {
            String cmd = entry.getValue().command();
            if (ProcessUtil.cmdHasBinary(cmd, "opencode") && !cmd.contains("grep")) {
                pids.add(entry.getKey());
            }
        }
        if (pids.isEmpty()) return Map.of();
        return ProcessUtil.getCwdBatch(pids.stream().mapToInt(Integer::intValue).toArray());
    }

    private boolean hasRecentSessions() {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var stmt = conn.prepareStatement(
                     "SELECT 1 FROM session WHERE time_updated > ? LIMIT 1")) {
            stmt.setLong(1, Instant.now().minusSeconds(300).toEpochMilli());
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            return false;
        }
    }

    // --- SQL queries ---

    private List<SessionRow> queryRecentSessions(Connection conn, int maxAgeSecs) throws SQLException {
        var rows = new ArrayList<SessionRow>();
        long cutoff = Instant.now().minusSeconds(maxAgeSecs).toEpochMilli();
        try (var stmt = conn.prepareStatement(
                "SELECT id, directory, title, time_created, time_updated FROM session " +
                        "WHERE time_updated > ? ORDER BY time_updated DESC LIMIT 20")) {
            stmt.setLong(1, cutoff);
            var rs = stmt.executeQuery();
            while (rs.next()) {
                rows.add(new SessionRow(
                        rs.getString("id"),
                        rs.getString("directory"),
                        rs.getString("title"),
                        rs.getLong("time_created"),
                        rs.getLong("time_updated")));
            }
        }
        return rows;
    }

    private TokenTotals queryTokenTotals(Connection conn, String sessionId) throws SQLException {
        var totals = new TokenTotals();
        try (var stmt = conn.prepareStatement(
                "SELECT " +
                        "SUM(json_extract(data, '$.tokens.input')) as inp, " +
                        "SUM(json_extract(data, '$.tokens.output')) as outp, " +
                        "SUM(json_extract(data, '$.tokens.cache.read')) as cr, " +
                        "SUM(json_extract(data, '$.tokens.cache.write')) as cw, " +
                        "COUNT(*) as turns " +
                        "FROM message WHERE session_id = ? AND json_extract(data, '$.role') = 'assistant'")) {
            stmt.setString(1, sessionId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                totals.input = rs.getLong("inp");
                totals.output = rs.getLong("outp");
                totals.cacheRead = rs.getLong("cr");
                totals.cacheWrite = rs.getLong("cw");
                totals.turnCount = rs.getInt("turns");
            }
        }
        // Get last context size from most recent assistant message
        try (var stmt = conn.prepareStatement(
                "SELECT json_extract(data, '$.tokens.input') as inp, " +
                        "json_extract(data, '$.tokens.cache.read') as cr, " +
                        "json_extract(data, '$.tokens.cache.write') as cw " +
                        "FROM message WHERE session_id = ? AND json_extract(data, '$.role') = 'assistant' " +
                        "ORDER BY time_created DESC LIMIT 1")) {
            stmt.setString(1, sessionId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                totals.lastContext = rs.getLong("inp") + rs.getLong("cr") + rs.getLong("cw");
            }
        }
        return totals;
    }

    private String queryModel(Connection conn, String sessionId) throws SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT json_extract(data, '$.modelID') FROM message " +
                        "WHERE session_id = ? AND json_extract(data, '$.role') = 'assistant' " +
                        "ORDER BY time_created DESC LIMIT 1")) {
            stmt.setString(1, sessionId);
            var rs = stmt.executeQuery();
            return rs.next() ? rs.getString(1) : "-";
        }
    }

    private String queryLastTool(Connection conn, String sessionId) throws SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT json_extract(p.data, '$.tool') as tool, " +
                        "json_extract(p.data, '$.state.title') as title " +
                        "FROM part p JOIN message m ON p.message_id = m.id " +
                        "WHERE m.session_id = ? AND json_extract(p.data, '$.type') = 'tool' " +
                        "ORDER BY p.time_created DESC LIMIT 1")) {
            stmt.setString(1, sessionId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                String tool = rs.getString("tool");
                String title = rs.getString("title");
                if (tool == null) return "";
                if (title != null && !title.isEmpty()) {
                    return tool + " " + SecretRedactor.redact(
                            title.length() > 40 ? title.substring(0, 40) : title);
                }
                return tool;
            }
        }
        return "";
    }

    private String queryInitialPrompt(Connection conn, String sessionId) throws SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT json_extract(p.data, '$.text') as txt " +
                        "FROM part p JOIN message m ON p.message_id = m.id " +
                        "WHERE m.session_id = ? AND json_extract(m.data, '$.role') = 'user' " +
                        "AND json_extract(p.data, '$.type') = 'text' " +
                        "ORDER BY p.time_created ASC LIMIT 1")) {
            stmt.setString(1, sessionId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                String text = rs.getString("txt");
                if (text != null && !text.isEmpty()) {
                    return text.length() > 50 ? text.substring(0, 50) : text;
                }
            }
        }
        return "";
    }

    private String queryFirstAssistantText(Connection conn, String sessionId) throws SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT json_extract(p.data, '$.text') as txt " +
                        "FROM part p JOIN message m ON p.message_id = m.id " +
                        "WHERE m.session_id = ? AND json_extract(m.data, '$.role') = 'assistant' " +
                        "AND json_extract(p.data, '$.type') = 'text' " +
                        "ORDER BY p.time_created ASC LIMIT 1")) {
            stmt.setString(1, sessionId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                String text = rs.getString("txt");
                if (text != null && !text.isEmpty()) {
                    return text.length() > 200 ? text.substring(0, 200) : text;
                }
            }
        }
        return "";
    }

    // --- Children ---

    private List<ChildProcess> buildChildren(int pid, SharedProcessData shared) {
        var children = new ArrayList<ChildProcess>();
        for (int cpid : ProcessUtil.allDescendants(pid, shared.childrenMap())) {
            var cproc = shared.processInfo().get(cpid);
            if (cproc != null) {
                var ports = shared.ports().getOrDefault(cpid, List.of());
                Integer port = ports.isEmpty() ? null : ports.getFirst();
                children.add(new ChildProcess(cpid, cproc.command(), cproc.rssKb(), port));
            }
        }
        return children;
    }

    // --- Data classes ---

    private record SessionRow(String id, String directory, String title, long timeCreated, long timeUpdated) {}

    private static class TokenTotals {
        long input, output, cacheRead, cacheWrite, lastContext;
        int turnCount;
    }
}
