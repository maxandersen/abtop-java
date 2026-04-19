package dev.abtop.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.abtop.model.*;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Collector for pi.dev coding agent sessions.
 *
 * Discovery:
 *   1. Find running `pi` processes via shared ps data
 *   2. Match PID → cwd via /proc or ps
 *   3. Scan ~/.pi/agent/sessions/{encoded-cwd}/ for JSONL files
 *
 * Session JSONL format:
 *   - type=session: {id, cwd, timestamp}
 *   - type=model_change: {modelId, provider}
 *   - type=thinking_level_change: {level}
 *   - type=message: {message: {role, content[], usage?}}
 *     - usage: {input, output, cacheRead, cacheWrite, totalTokens, cost}
 *     - content blocks: {type: text|toolCall|toolResult|thinking, ...}
 *     - toolCall: {name, arguments}
 *
 * Directory naming: cwd encoded as --path-segments-- (double-dash delimited)
 */
public class PiCollector implements AgentCollector {

    private static final ObjectMapper MAPPER = Json.MAPPER;
    private static final int MAX_LINE_BYTES = 10 * 1024 * 1024;

    private final Path sessionsDir;
    private final Map<String, PiJsonlResult> transcriptCache = new HashMap<>();

    public PiCollector() {
        String home = System.getProperty("user.home", "");
        this.sessionsDir = Path.of(home, ".pi", "agent", "sessions");
    }

    @Override
    public List<AgentSession> collect(SharedProcessData shared) {
        if (!Files.isDirectory(sessionsDir)) return List.of();

        // Step 1: Find running pi processes
        var piPids = findPiPids(shared.processInfo());
        // Batch-get cwds via single lsof call
        var pidToCwd = ProcessUtil.getCwdBatch(piPids.stream().mapToInt(Integer::intValue).toArray());

        var sessions = new ArrayList<AgentSession>();
        var seenFiles = new HashSet<Path>();

        // Step 2: For each running pi, find its session file
        // Deduplicate: multiple PIDs may share the same cwd → same JSONL file.
        // Keep the PID with the highest CPU (most likely the active one).
        var fileTobestPid = new HashMap<Path, Integer>();
        for (var entry : pidToCwd.entrySet()) {
            int pid = entry.getKey();
            String cwd = entry.getValue();
            String encoded = encodeCwd(cwd);
            Path dir = sessionsDir.resolve(encoded);
            if (!Files.isDirectory(dir)) continue;

            Path latestFile = findLatestJsonl(dir);
            if (latestFile == null) continue;

            Integer existing = fileTobestPid.get(latestFile);
            if (existing == null) {
                fileTobestPid.put(latestFile, pid);
            } else {
                // Pick the PID with higher CPU usage
                var curInfo = shared.processInfo().get(existing);
                var newInfo = shared.processInfo().get(pid);
                double curCpu = curInfo != null ? curInfo.cpuPct() : 0;
                double newCpu = newInfo != null ? newInfo.cpuPct() : 0;
                if (newCpu > curCpu) fileTobestPid.put(latestFile, pid);
            }
        }

        for (var entry : fileTobestPid.entrySet()) {
            seenFiles.add(entry.getKey());
            var session = loadSession(entry.getValue(), entry.getKey(), shared);
            if (session != null) sessions.add(session);
        }

        // Step 3: Recently finished sessions — scan all dirs for JSONL < 5 min old
        try (var dirs = Files.list(sessionsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path latest = findLatestJsonl(dir);
                if (latest != null && !seenFiles.contains(latest)) {
                    try {
                        var age = Duration.between(
                                Files.getLastModifiedTime(latest).toInstant(), Instant.now());
                        if (age.toSeconds() <= 300) {
                            var session = loadSession(0, latest, shared);
                            if (session != null) sessions.add(session);
                        }
                    } catch (IOException ignored) {}
                }
            });
        } catch (IOException ignored) {}

        // Evict cache for sessions no longer active
        var activeFiles = new HashSet<String>();
        for (var s : sessions) activeFiles.add(s.getSessionId());
        transcriptCache.keySet().retainAll(activeFiles);

        sessions.sort(Comparator.comparingLong(AgentSession::getStartedAt).reversed());
        return sessions;
    }

    private PiJsonlResult updateCache(Path jsonlPath) {
        String key = jsonlPath.toString();
        var prev = transcriptCache.get(key);
        long fromOffset = prev != null ? prev.newOffset : 0;

        var delta = parsePiJsonl(jsonlPath, fromOffset);
        if (delta == null) return prev; // file gone or empty

        if (prev != null && fromOffset > 0 && delta.newOffset >= fromOffset) {
            prev.mergeFrom(delta);
            return prev;
        } else {
            // Full re-scan or first read
            transcriptCache.put(key, delta);
            return delta;
        }
    }

    private AgentSession loadSession(int pid, Path jsonlPath, SharedProcessData shared) {
        var result = updateCache(jsonlPath);
        if (result == null) return null;

        var procInfo = pid > 0 ? shared.processInfo().get(pid) : null;
        long memMb = procInfo != null ? procInfo.rssKb() / 1024 : 0;
        boolean pidAlive = procInfo != null;

        String projectName = result.cwd.contains("/")
                ? result.cwd.substring(result.cwd.lastIndexOf('/') + 1) : "?";

        // Status detection
        SessionStatus status;
        if (!pidAlive) {
            status = SessionStatus.DONE;
        } else {
            long sinceActivity = Duration.between(result.lastActivity, Instant.now()).toSeconds();
            if (sinceActivity < 30) {
                status = SessionStatus.WORKING;
            } else {
                boolean cpuActive = procInfo.cpuPct() > 1.0;
                boolean hasActiveChild = ProcessUtil.hasActiveDescendant(
                        pid, shared.childrenMap(), shared.processInfo(), 5.0);
                status = (cpuActive || hasActiveChild) ? SessionStatus.WORKING : SessionStatus.WAITING;
            }
        }

        List<String> currentTasks;
        if (!result.currentTask.isEmpty()) {
            currentTasks = List.of(result.currentTask);
        } else if (!pidAlive) {
            currentTasks = List.of("finished");
        } else if (status == SessionStatus.WAITING) {
            currentTasks = List.of("waiting for input");
        } else {
            currentTasks = List.of("thinking...");
        }

        // Context window — pi uses Claude models, same logic
        long contextWindow = result.model.contains("[1m]") || result.maxContextTokens > 200_000
                ? 1_000_000 : 200_000;
        double contextPercent = contextWindow > 0 && result.lastContextTokens > 0
                ? (result.lastContextTokens * 100.0 / contextWindow) : 0.0;

        // Children
        var children = pid > 0 ? buildChildren(pid, shared) : List.<ChildProcess>of();

        var session = new AgentSession("pi", Math.max(pid, 0));
        session.setSessionId(result.sessionId);
        session.setCwd(result.cwd);
        session.setProjectName(projectName);
        session.setStartedAt(result.startedAt);
        session.setStatus(status);
        session.setModel(result.model);
        session.setEffort(result.thinkingLevel);
        session.setContextPercent(contextPercent);
        session.setTotalInputTokens(result.totalInput);
        session.setTotalOutputTokens(result.totalOutput);
        session.setTotalCacheRead(result.totalCacheRead);
        session.setTotalCacheCreate(result.totalCacheWrite);
        session.setTurnCount(result.turnCount);
        session.setCurrentTasks(currentTasks);
        session.setMemMb(memMb);
        session.setTokenHistory(result.tokenHistory);
        session.setChildren(children);
        session.setInitialPrompt(result.initialPrompt);
        session.setFirstAssistantText(result.firstAssistantText);
        return session;
    }

    // --- JSONL parsing ---

    /** Parse full file (convenience for first read). */
    static PiJsonlResult parsePiJsonl(Path path) {
        return parsePiJsonl(path, 0);
    }

    /** Parse file from offset. Returns delta result with newOffset set. */
    static PiJsonlResult parsePiJsonl(Path path, long fromOffset) {
        var result = new PiJsonlResult();
        result.newOffset = fromOffset;

        long fileLen;
        try { fileLen = Files.size(path); }
        catch (IOException e) { return null; }

        if (fileLen == fromOffset) {
            // No new data. For full scan of empty file, return null.
            if (fromOffset == 0) return null;
            result.newOffset = fileLen;
            return result;
        }
        // File shrank — reset
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
                try { val = MAPPER.readTree(line); }
                catch (Exception e) { continue; }

                // Update last activity
                var ts = val.path("timestamp").asText(null);
                if (ts != null) {
                    try {
                        var dt = Instant.parse(ts);
                        if (dt.isAfter(result.lastActivity)) result.lastActivity = dt;
                    } catch (Exception ignored) {}
                }

                String type = val.path("type").asText("");
                switch (type) {
                    case "session" -> {
                        result.sessionId = val.path("id").asText("");
                        result.cwd = val.path("cwd").asText("");
                        var tsStr = val.path("timestamp").asText(null);
                        if (tsStr != null) {
                            try { result.startedAt = Instant.parse(tsStr).toEpochMilli(); }
                            catch (Exception ignored) {}
                        }
                    }
                    case "model_change" -> {
                        result.model = val.path("modelId").asText(result.model);
                        result.provider = val.path("provider").asText(result.provider);
                    }
                    case "thinking_level_change" -> {
                        var level = val.path("level").asText(null);
                        if (level != null) result.thinkingLevel = level;
                    }
                    case "message" -> parseMessage(val, result);
                }
            }
            result.newOffset = bytesRead;
        } catch (IOException ignored) {}

        // For full scans, null if no session found; for incremental, always return result
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

    private static void parseMessage(JsonNode val, PiJsonlResult result) {
        var msg = val.path("message");
        if (msg.isMissingNode()) return;

        String role = msg.path("role").asText("");

        // Usage (cumulative — each assistant message has its own usage)
        var usage = msg.get("usage");
        if (usage != null && usage.isObject()) {
            long inp = usage.path("input").asLong(0);
            long out = usage.path("output").asLong(0);
            long cr = usage.path("cacheRead").asLong(0);
            long cw = usage.path("cacheWrite").asLong(0);
            result.totalInput += inp;
            result.totalOutput += out;
            result.totalCacheRead += cr;
            result.totalCacheWrite += cw;
            result.lastContextTokens = inp + cr + cw;
            if (result.lastContextTokens > result.maxContextTokens) {
                result.maxContextTokens = result.lastContextTokens;
            }
            if (result.tokenHistory.size() < 10_000) {
                result.tokenHistory.add(inp + out + cr + cw);
            }
        }

        var content = msg.get("content");
        if (content == null || !content.isArray()) return;

        if ("user".equals(role)) {
            if (result.initialPrompt.isEmpty()) {
                for (var block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        String text = block.path("text").asText("");
                        result.initialPrompt = text.length() > 50 ? text.substring(0, 50) : text;
                        break;
                    }
                }
            }
        } else if ("assistant".equals(role)) {
            result.turnCount++;
            result.currentTask = ""; // reset per turn

            // First assistant text
            if (result.firstAssistantText.isEmpty()) {
                for (var block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        String text = block.path("text").asText("");
                        String normalized = String.join(" ",
                                text.lines().map(String::trim).filter(l -> !l.isEmpty()).toList());
                        if (!normalized.isEmpty()) {
                            result.firstAssistantText = normalized.length() > 200
                                    ? normalized.substring(0, 200) : normalized;
                            break;
                        }
                    }
                }
            }

            // Last tool call from this turn
            for (int i = content.size() - 1; i >= 0; i--) {
                var block = content.get(i);
                if ("toolCall".equals(block.path("type").asText())) {
                    String name = block.path("name").asText("?");
                    var args = block.get("arguments");
                    String arg = "";
                    if (args != null) {
                        // Try common arg patterns
                        var filePath = args.path("path").asText(null);
                        if (filePath == null) filePath = args.path("file_path").asText(null);
                        var command = args.path("command").asText(null);
                        if (filePath != null) {
                            int slash = filePath.lastIndexOf('/');
                            arg = slash >= 0 ? filePath.substring(slash + 1) : filePath;
                        } else if (command != null) {
                            String firstLine = command.lines().findFirst().orElse(command);
                            arg = SecretRedactor.redact(
                                    firstLine.length() > 40 ? firstLine.substring(0, 40) : firstLine);
                        }
                    }
                    result.currentTask = arg.isEmpty() ? name : name + " " + arg;
                    break;
                }
            }
        }
    }

    // --- Process discovery ---

    private List<Integer> findPiPids(Map<Integer, ProcessUtil.ProcInfo> processInfo) {
        var pids = new ArrayList<Integer>();
        for (var entry : processInfo.entrySet()) {
            String cmd = entry.getValue().command();
            if (ProcessUtil.cmdHasBinary(cmd, "pi") && !cmd.contains("grep")
                    && !cmd.contains("picocli") && !cmd.contains("pipeline")) {
                pids.add(entry.getKey());
            }
        }
        return pids;
    }

    /** Encode cwd for pi directory naming: /Users/max/code → --Users-max-code-- */
    static String encodeCwd(String cwd) {
        // Pi uses double-dash delimited: --segments--
        return "--" + cwd.replace("/", "-").replaceFirst("^-", "") + "--";
    }

    private Path findLatestJsonl(Path dir) {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".jsonl"))
                    .filter(p -> !Files.isSymbolicLink(p))
                    .max(Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime(p).toInstant(); }
                        catch (IOException e) { return Instant.EPOCH; }
                    }))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
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

    /** Parsed result from pi JSONL. Supports incremental parsing. */
    static class PiJsonlResult {
        String sessionId = "";
        String cwd = "";
        long startedAt;
        String model = "-";
        String provider = "";
        String thinkingLevel = "";
        int turnCount;
        String currentTask = "";
        Instant lastActivity = Instant.EPOCH;
        String initialPrompt = "";
        String firstAssistantText = "";
        long totalInput;
        long totalOutput;
        long totalCacheRead;
        long totalCacheWrite;
        long lastContextTokens;
        long maxContextTokens;
        List<Long> tokenHistory = new ArrayList<>();
        long newOffset;

        /** Merge a delta (from incremental parse) into this result. */
        void mergeFrom(PiJsonlResult delta) {
            if (!delta.sessionId.isEmpty()) this.sessionId = delta.sessionId;
            if (!delta.cwd.isEmpty()) this.cwd = delta.cwd;
            if (!"-".equals(delta.model)) this.model = delta.model;
            if (!delta.provider.isEmpty()) this.provider = delta.provider;
            if (!delta.thinkingLevel.isEmpty()) this.thinkingLevel = delta.thinkingLevel;
            this.totalInput += delta.totalInput;
            this.totalOutput += delta.totalOutput;
            this.totalCacheRead += delta.totalCacheRead;
            this.totalCacheWrite += delta.totalCacheWrite;
            if (delta.lastContextTokens > 0) this.lastContextTokens = delta.lastContextTokens;
            if (delta.maxContextTokens > this.maxContextTokens) this.maxContextTokens = delta.maxContextTokens;
            this.turnCount += delta.turnCount;
            if (delta.turnCount > 0) this.currentTask = delta.currentTask;
            if (delta.lastActivity.isAfter(this.lastActivity)) this.lastActivity = delta.lastActivity;
            if (this.initialPrompt.isEmpty() && !delta.initialPrompt.isEmpty()) this.initialPrompt = delta.initialPrompt;
            if (this.firstAssistantText.isEmpty() && !delta.firstAssistantText.isEmpty()) this.firstAssistantText = delta.firstAssistantText;
            this.tokenHistory.addAll(delta.tokenHistory);
            this.newOffset = delta.newOffset;
        }
    }
}
