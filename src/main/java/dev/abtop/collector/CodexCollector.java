package dev.abtop.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.abtop.model.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Collector for OpenAI Codex CLI sessions.
 * Discovery: ps → lsof → rollout-*.jsonl parsing.
 */
public class CodexCollector implements AgentCollector {

    private static final ObjectMapper MAPPER = Json.MAPPER;
    private static final int MAX_LINE_BYTES = 10 * 1024 * 1024;

    private final Path sessionsDir;
    private final Path stateDbPath;
    private RateLimitInfo lastRateLimit;
    private final Map<String, CodexJsonlResult> transcriptCache = new HashMap<>();

    public CodexCollector() {
        String home = System.getProperty("user.home", "");
        this.sessionsDir = Path.of(home, ".codex", "sessions");
        this.stateDbPath = Path.of(home, ".codex", "state_5.sqlite");
    }

    @Override
    public List<AgentSession> collect(SharedProcessData shared) {
        lastRateLimit = null;

        // Step 1: Find running codex processes
        var codexPids = findCodexPids(shared.processInfo());

        // Try SQLite-based discovery first (newer Codex versions)
        if (Files.exists(stateDbPath) && !codexPids.isEmpty()) {
            var sqliteSessions = collectFromSqlite(codexPids, shared);
            if (!sqliteSessions.isEmpty()) return sqliteSessions;
        }

        // Fall back to JSONL-based discovery (older Codex versions)
        if (!Files.isDirectory(sessionsDir)) return List.of();

        var justPids = codexPids.keySet().stream().mapToInt(Integer::intValue).toArray();
        var pidToJsonl = mapPidToJsonl(justPids);

        var sessions = new ArrayList<AgentSession>();
        var seenJsonl = new HashSet<Path>();

        // Active sessions
        for (var entry : pidToJsonl.entrySet()) {
            int pid = entry.getKey();
            Path jsonlPath = entry.getValue();
            boolean isExec = codexPids.getOrDefault(pid, false);
            var loaded = loadSession(pid, isExec, jsonlPath, shared);
            if (loaded != null) {
                seenJsonl.add(jsonlPath);
                sessions.add(loaded);
            }
        }

        // Recently finished sessions
        todaySessionDir().ifPresent(dir -> {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".jsonl"))
                        .filter(p -> !Files.isSymbolicLink(p))
                        .filter(p -> !seenJsonl.contains(p))
                        .filter(p -> {
                            try {
                                var age = Duration.between(
                                        Files.getLastModifiedTime(p).toInstant(), Instant.now());
                                return age.toSeconds() <= 300;
                            } catch (IOException e) { return false; }
                        })
                        .forEach(p -> {
                            var s = loadSession(-1, false, p, shared);
                            if (s != null) sessions.add(s);
                        });
            } catch (IOException ignored) {}
        });

        // Evict cache for sessions no longer active
        var activeKeys = new HashSet<String>();
        for (var s : sessions) activeKeys.add(s.getSessionId());
        transcriptCache.keySet().retainAll(activeKeys);

        sessions.sort(Comparator.comparingLong(AgentSession::getStartedAt).reversed());
        return sessions;
    }

    private CodexJsonlResult updateCache(Path jsonlPath) {
        String key = jsonlPath.toString();
        var prev = transcriptCache.get(key);
        long fromOffset = prev != null ? prev.newOffset : 0;

        var delta = parseCodexJsonl(jsonlPath, fromOffset);
        if (delta == null) return prev;

        if (prev != null && fromOffset > 0 && delta.newOffset >= fromOffset) {
            prev.mergeFrom(delta);
            return prev;
        } else {
            transcriptCache.put(key, delta);
            return delta;
        }
    }

    @Override
    public Optional<RateLimitInfo> liveRateLimit() {
        if (lastRateLimit != null) return Optional.of(lastRateLimit);
        return RateLimitReader.readCodexCache();
    }

    private AgentSession loadSession(int pid, boolean isExec, Path jsonlPath, SharedProcessData shared) {
        var result = updateCache(jsonlPath);
        if (result == null) return null;

        var procInfo = pid > 0 ? shared.processInfo().get(pid) : null;
        long memMb = procInfo != null ? procInfo.rssKb() / 1024 : 0;
        int displayPid = Math.max(pid, 0);
        String projectName = result.cwd.contains("/")
                ? result.cwd.substring(result.cwd.lastIndexOf('/') + 1) : "?";

        boolean pidAlive = procInfo != null;
        SessionStatus status;
        if (!pidAlive || (isExec && result.taskComplete)) {
            status = SessionStatus.DONE;
        } else {
            long sinceActivity = Duration.between(result.lastActivity, Instant.now()).toSeconds();
            if (sinceActivity < 30) {
                status = SessionStatus.WORKING;
            } else {
                boolean cpuActive = procInfo.cpuPct() > 1.0;
                boolean hasActiveChild = pid > 0 && ProcessUtil.hasActiveDescendant(
                        pid, shared.childrenMap(), shared.processInfo(), 5.0);
                status = (cpuActive || hasActiveChild) ? SessionStatus.WORKING : SessionStatus.WAITING;
            }
        }

        List<String> currentTasks;
        if (!result.currentTask.isEmpty()) {
            currentTasks = List.of(result.currentTask);
        } else if (!pidAlive || (isExec && result.taskComplete)) {
            currentTasks = List.of("finished");
        } else if (status == SessionStatus.WAITING) {
            currentTasks = List.of("waiting for input");
        } else {
            currentTasks = List.of("thinking...");
        }

        double contextPercent = result.contextWindow > 0 && result.lastContextTokens > 0
                ? (result.lastContextTokens * 100.0 / result.contextWindow) : 0.0;

        var children = pid > 0 ? buildChildren(pid, shared) : List.<ChildProcess>of();

        // Update rate limit
        if (result.rateLimit != null) {
            if (lastRateLimit == null || (result.rateLimit.updatedAt() != null
                    && (lastRateLimit.updatedAt() == null
                    || result.rateLimit.updatedAt() > lastRateLimit.updatedAt()))) {
                RateLimitReader.writeCodexCache(result.rateLimit);
                lastRateLimit = result.rateLimit;
            }
        }

        var session = new AgentSession("codex", displayPid);
        session.setSessionId(result.sessionId);
        session.setCwd(result.cwd);
        session.setProjectName(projectName);
        session.setStartedAt(result.startedAt);
        session.setStatus(status);
        session.setModel(result.model);
        session.setEffort(result.effort);
        session.setContextPercent(contextPercent);
        session.setTotalInputTokens(result.totalInput);
        session.setTotalOutputTokens(result.totalOutput);
        session.setTotalCacheRead(result.totalCacheRead);
        session.setTurnCount(result.turnCount);
        session.setCurrentTasks(currentTasks);
        session.setMemMb(memMb);
        session.setVersion(result.version);
        session.setGitBranch(result.gitBranch);
        session.setTokenHistory(result.tokenHistory);
        session.setChildren(children);
        session.setInitialPrompt(result.initialPrompt);
        return session;
    }

    // --- SQLite-based collection (newer Codex) ---

    private List<AgentSession> collectFromSqlite(Map<Integer, Boolean> codexPids, SharedProcessData shared) {
        var sessions = new ArrayList<AgentSession>();

        // Batch-get cwds via single lsof call
        var pidToCwd = ProcessUtil.getCwdBatch(
                codexPids.keySet().stream().mapToInt(Integer::intValue).toArray());
        if (pidToCwd.isEmpty()) return sessions;

        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + stateDbPath)) {
            // Find threads matching running PIDs by cwd
            for (var entry : pidToCwd.entrySet()) {
                int pid = entry.getKey();
                String cwd = entry.getValue();
                boolean isExec = codexPids.getOrDefault(pid, false);

                try (var stmt = conn.prepareStatement(
                        "SELECT id, cwd, title, model, reasoning_effort, tokens_used, " +
                        "cli_version, git_branch, first_user_message, " +
                        "created_at_ms, updated_at_ms, rollout_path " +
                        "FROM threads WHERE cwd = ? AND archived = 0 " +
                        "ORDER BY updated_at_ms DESC LIMIT 1")) {
                    stmt.setString(1, cwd);
                    var rs = stmt.executeQuery();
                    if (rs.next()) {
                        var session = loadFromSqliteRow(rs, pid, isExec, shared);
                        if (session != null) sessions.add(session);
                    }
                }
            }
        } catch (SQLException ignored) {}

        sessions.sort(Comparator.comparingLong(AgentSession::getStartedAt).reversed());
        return sessions;
    }

    private AgentSession loadFromSqliteRow(java.sql.ResultSet rs, int pid, boolean isExec,
                                            SharedProcessData shared) throws SQLException {
        String threadId = rs.getString("id");
        String cwd = rs.getString("cwd");
        String title = rs.getString("title");
        String model = rs.getString("model");
        String effort = rs.getString("reasoning_effort");
        long tokensUsed = rs.getLong("tokens_used");
        String version = rs.getString("cli_version");
        String gitBranch = rs.getString("git_branch");
        String firstUserMsg = rs.getString("first_user_message");
        long createdAtMs = rs.getLong("created_at_ms");
        long updatedAtMs = rs.getLong("updated_at_ms");

        var procInfo = pid > 0 ? shared.processInfo().get(pid) : null;
        boolean pidAlive = procInfo != null;
        long memMb = procInfo != null ? procInfo.rssKb() / 1024 : 0;
        String projectName = cwd.contains("/") ? cwd.substring(cwd.lastIndexOf('/') + 1) : "?";

        SessionStatus status;
        if (!pidAlive) {
            status = SessionStatus.DONE;
        } else {
            long sinceUpdate = Duration.between(Instant.ofEpochMilli(updatedAtMs), Instant.now()).toSeconds();
            if (sinceUpdate < 30) {
                status = SessionStatus.WORKING;
            } else {
                boolean cpuActive = procInfo.cpuPct() > 1.0;
                boolean hasActiveChild = ProcessUtil.hasActiveDescendant(
                        pid, shared.childrenMap(), shared.processInfo(), 5.0);
                status = (cpuActive || hasActiveChild) ? SessionStatus.WORKING : SessionStatus.WAITING;
            }
        }

        List<String> currentTasks;
        if (!pidAlive) {
            currentTasks = List.of("finished");
        } else if (status == SessionStatus.WAITING) {
            currentTasks = List.of("waiting for input");
        } else {
            currentTasks = List.of("thinking...");
        }

        var children = pid > 0 ? buildChildren(pid, shared) : List.<ChildProcess>of();

        String initialPrompt = firstUserMsg != null && !firstUserMsg.isEmpty()
                ? (firstUserMsg.length() > 50 ? firstUserMsg.substring(0, 50) : firstUserMsg)
                : (title != null ? title : "");

        var session = new AgentSession("codex", pid);
        session.setSessionId(threadId);
        session.setCwd(cwd);
        session.setProjectName(projectName);
        session.setStartedAt(createdAtMs);
        session.setStatus(status);
        session.setModel(model != null ? model : "-");
        session.setEffort(effort != null ? effort : "");
        session.setTotalInputTokens(tokensUsed); // total only available as aggregate
        session.setTurnCount(0); // not available from threads table
        session.setCurrentTasks(currentTasks);
        session.setMemMb(memMb);
        session.setVersion(version != null ? version : "");
        session.setGitBranch(gitBranch != null ? gitBranch : "");
        session.setChildren(children);
        session.setInitialPrompt(initialPrompt);
        return session;
    }

    // --- JSONL parsing ---

    /** Parse full file (convenience). */
    static CodexJsonlResult parseCodexJsonl(Path path) {
        return parseCodexJsonl(path, 0);
    }

    /** Parse file from offset. Returns delta result with newOffset set. */
    static CodexJsonlResult parseCodexJsonl(Path path, long fromOffset) {
        var result = new CodexJsonlResult();
        result.newOffset = fromOffset;

        long fileLen;
        try { fileLen = Files.size(path); }
        catch (IOException e) { return result; }

        if (fileLen == fromOffset) {
            if (fromOffset == 0) return null;
            result.newOffset = fileLen;
            return result;
        }
        long effectiveOffset = fileLen < fromOffset ? 0 : fromOffset;

        try {
            result.lastActivity = Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ignored) {}

        try (var raf = new RandomAccessFile(path.toFile(), "r")) {
            if (effectiveOffset > 0) raf.seek(effectiveOffset);
            long bytesRead = effectiveOffset;

            String line;
            while ((line = readLineBounded(raf, MAX_LINE_BYTES)) != null) {
                bytesRead = raf.getFilePointer();
                line = line.trim();
                if (line.isEmpty()) continue;

                JsonNode val;
                try {
                    val = MAPPER.readTree(line);
                } catch (Exception e) { continue; }

                // Update last activity
                var tsNode = val.get("timestamp");
                if (tsNode != null && tsNode.isTextual()) {
                    try {
                        var dt = Instant.parse(tsNode.asText());
                        if (dt.isAfter(result.lastActivity)) result.lastActivity = dt;
                    } catch (Exception ignored) {}
                }

                String type = val.path("type").asText("");
                var payload = val.get("payload");
                if (payload == null) payload = MAPPER.createObjectNode();

                switch (type) {
                    case "session_meta" -> {
                        if (payload.has("id")) result.sessionId = payload.get("id").asText();
                        if (payload.has("cwd")) result.cwd = payload.get("cwd").asText();
                        if (payload.has("cli_version")) result.version = payload.get("cli_version").asText();
                        if (payload.has("timestamp")) {
                            try {
                                result.startedAt = Instant.parse(payload.get("timestamp").asText()).toEpochMilli();
                            } catch (Exception ignored) {}
                        }
                        result.gitBranch = payload.path("git").path("branch").asText("");
                    }
                    case "event_msg" -> {
                        String subType = payload.path("type").asText("");
                        switch (subType) {
                            case "task_started" -> {
                                if (payload.has("model_context_window"))
                                    result.contextWindow = payload.get("model_context_window").asLong();
                            }
                            case "user_message" -> {
                                if (result.initialPrompt.isEmpty() && payload.has("message")) {
                                    String msg = payload.get("message").asText("");
                                    String truncated = msg.length() > 120 ? msg.substring(0, 120) : msg;
                                    result.initialPrompt = SecretRedactor.redact(truncated);
                                }
                            }
                            case "token_count" -> parseTokenCount(val, payload, result);
                            case "agent_message" -> result.turnCount++;
                            case "task_complete" -> result.taskComplete = true;
                        }
                    }
                    case "response_item" -> {
                        if ("function_call".equals(payload.path("type").asText())) {
                            String name = payload.path("name").asText("");
                            String arg = "";
                            var argsStr = payload.path("arguments").asText(null);
                            if (argsStr != null) {
                                try {
                                    var argsNode = MAPPER.readTree(argsStr);
                                    var fp = argsNode.path("file_path").asText(null);
                                    var cmd = argsNode.path("cmd").asText(null);
                                    if (fp != null) {
                                        int slash = fp.lastIndexOf('/');
                                        arg = slash >= 0 ? fp.substring(slash + 1) : fp;
                                    } else if (cmd != null) {
                                        arg = SecretRedactor.redact(cmd);
                                    }
                                } catch (Exception ignored) {}
                            }
                            result.currentTask = arg.isEmpty() ? name : name + " " + arg;
                        }
                    }
                    case "turn_context" -> {
                        if (payload.has("model")) result.model = payload.get("model").asText();
                        if (payload.has("effort")) result.effort = payload.get("effort").asText();
                        if (payload.has("model_context_window"))
                            result.contextWindow = payload.get("model_context_window").asLong();
                    }
                }
            }
            result.newOffset = bytesRead;
        } catch (IOException ignored) {}

        if (effectiveOffset == 0 && result.sessionId.isEmpty()) return null;
        return result;
    }

    /**
     * Read a line from RandomAccessFile, bounded to maxBytes.
     * Returns null at EOF. Handles multi-byte UTF-8.
     */
    private static String readLineBounded(RandomAccessFile raf, int maxBytes) throws IOException {
        var buf = new java.io.ByteArrayOutputStream();
        int b;
        int count = 0;
        while ((b = raf.read()) != -1) {
            count++;
            if (b == '\n') return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (count > maxBytes) {
                raf.seek(raf.length());
                return null;
            }
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void parseTokenCount(JsonNode val, JsonNode payload, CodexJsonlResult result) {
        var info = payload.get("info");
        if (info == null) return;

        var total = info.get("total_token_usage");
        if (total != null && total.isObject()) {
            result.totalInput = total.path("input_tokens").asLong(0);
            result.totalOutput = total.path("output_tokens").asLong(0);
            long cache = total.path("cached_input_tokens").asLong(-1);
            if (cache < 0) cache = total.path("cache_read_input_tokens").asLong(0);
            result.totalCacheRead = cache;
        }

        var last = info.get("last_token_usage");
        if (last != null && last.isObject()) {
            long inp = last.path("input_tokens").asLong(0);
            long out = last.path("output_tokens").asLong(0);
            long cache = last.path("cached_input_tokens").asLong(-1);
            if (cache < 0) cache = last.path("cache_read_input_tokens").asLong(0);
            result.lastContextTokens = inp + cache;
            if (result.tokenHistory.size() < 10_000) {
                result.tokenHistory.add(inp + out + cache);
            }
        }

        if (info.has("model_context_window")) {
            result.contextWindow = info.get("model_context_window").asLong();
        }

        // Rate limits
        var rl = payload.get("rate_limits");
        if (rl != null && rl.isObject()) {
            Long eventSecs = null;
            var tsNode = val.get("timestamp");
            if (tsNode != null) {
                try { eventSecs = Instant.parse(tsNode.asText()).getEpochSecond(); }
                catch (Exception ignored) {}
            }

            Double fiveHourPct = null;
            Long fiveHourResets = null;
            Double sevenDayPct = null;
            Long sevenDayResets = null;

            for (String slot : List.of("primary", "secondary")) {
                var w = rl.get(slot);
                if (w == null || !w.isObject()) continue;
                long mins = w.path("window_minutes").asLong(0);
                Double pct = w.has("used_percent") ? w.get("used_percent").asDouble() : null;
                Long resets = w.has("resets_at") ? w.get("resets_at").asLong() : null;
                if (mins <= 300) {
                    fiveHourPct = pct;
                    fiveHourResets = resets;
                } else {
                    sevenDayPct = pct;
                    sevenDayResets = resets;
                }
            }
            result.rateLimit = new RateLimitInfo("codex", fiveHourPct, fiveHourResets,
                    sevenDayPct, sevenDayResets, eventSecs);
        }
    }

    // --- Process discovery ---

    /** Find codex PIDs from shared process data. Returns pid → isExec map. */
    private Map<Integer, Boolean> findCodexPids(Map<Integer, ProcessUtil.ProcInfo> processInfo) {
        var pids = new HashMap<Integer, Boolean>();
        for (var entry : processInfo.entrySet()) {
            String cmd = entry.getValue().command();
            if (ProcessUtil.cmdHasBinary(cmd, "codex")
                    && !cmd.contains("app-server")
                    && !cmd.contains("grep")) {
                pids.put(entry.getKey(), cmd.contains(" exec"));
            }
        }
        return pids;
    }

    /** Map codex PIDs to their open rollout-*.jsonl files via lsof. */
    private Map<Integer, Path> mapPidToJsonl(int[] pids) {
        var map = new HashMap<Integer, Path>();
        if (pids.length == 0) return map;

        var args = new ArrayList<>(List.of("lsof", "-F", "pn"));
        for (int pid : pids) args.add("-p" + pid);

        try {
            var pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            var proc = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                Integer currentPid = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("p")) {
                        try { currentPid = Integer.parseInt(line.substring(1)); }
                        catch (NumberFormatException e) { currentPid = null; }
                    } else if (line.startsWith("n") && currentPid != null) {
                        String name = line.substring(1);
                        if (name.contains("rollout-") && name.endsWith(".jsonl")) {
                            map.put(currentPid, Path.of(name));
                        }
                    }
                }
            }
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (Exception ignored) {}
        return map;
    }

    private Optional<Path> todaySessionDir() {
        var now = LocalDate.now();
        Path dir = sessionsDir
                .resolve(String.format("%04d", now.getYear()))
                .resolve(String.format("%02d", now.getMonthValue()))
                .resolve(String.format("%02d", now.getDayOfMonth()));
        return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
    }

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

    /** Parsed result from Codex JSONL. Supports incremental parsing. */
    static class CodexJsonlResult {
        String sessionId = "";
        String cwd = "";
        long startedAt;
        String model = "-";
        String effort = "";
        String version = "";
        String gitBranch = "";
        long contextWindow;
        int turnCount;
        String currentTask = "";
        boolean taskComplete;
        Instant lastActivity = Instant.EPOCH;
        String initialPrompt = "";
        long totalInput;
        long totalOutput;
        long totalCacheRead;
        long lastContextTokens;
        List<Long> tokenHistory = new ArrayList<>();
        RateLimitInfo rateLimit;
        long newOffset;

        /** Merge a delta (from incremental parse) into this result. */
        void mergeFrom(CodexJsonlResult delta) {
            if (!delta.sessionId.isEmpty()) this.sessionId = delta.sessionId;
            if (!delta.cwd.isEmpty()) this.cwd = delta.cwd;
            if (!"-".equals(delta.model)) this.model = delta.model;
            if (!delta.effort.isEmpty()) this.effort = delta.effort;
            if (!delta.version.isEmpty()) this.version = delta.version;
            if (!delta.gitBranch.isEmpty()) this.gitBranch = delta.gitBranch;
            if (delta.contextWindow > 0) this.contextWindow = delta.contextWindow;
            this.turnCount += delta.turnCount;
            // Codex token_count gives cumulative totals, so take the latest (not sum)
            if (delta.totalInput > 0) this.totalInput = delta.totalInput;
            if (delta.totalOutput > 0) this.totalOutput = delta.totalOutput;
            if (delta.totalCacheRead > 0) this.totalCacheRead = delta.totalCacheRead;
            if (delta.lastContextTokens > 0) this.lastContextTokens = delta.lastContextTokens;
            if (delta.turnCount > 0) this.currentTask = delta.currentTask;
            this.taskComplete = this.taskComplete || delta.taskComplete;
            if (delta.lastActivity.isAfter(this.lastActivity)) this.lastActivity = delta.lastActivity;
            if (this.initialPrompt.isEmpty() && !delta.initialPrompt.isEmpty()) this.initialPrompt = delta.initialPrompt;
            this.tokenHistory.addAll(delta.tokenHistory);
            if (delta.rateLimit != null) this.rateLimit = delta.rateLimit;
            this.newOffset = delta.newOffset;
        }
    }
}
