package dev.abtop.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.abtop.model.*;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Collector for Claude Code sessions.
 * Discovery: ~/.claude/sessions/{PID}.json → transcript JSONL parsing.
 */
public class ClaudeCollector implements AgentCollector {

    private static final ObjectMapper MAPPER = Json.MAPPER;
    private static final int MAX_LINE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final Path sessionsDir;
    private final Path projectsDir;
    private final Map<String, TranscriptResult> transcriptCache = new HashMap<>();

    public ClaudeCollector() {
        Path base = claudeBaseDir();
        this.sessionsDir = base.resolve("sessions");
        this.projectsDir = base.resolve("projects");
    }

    // Visible for testing
    ClaudeCollector(Path sessionsDir, Path projectsDir) {
        this.sessionsDir = sessionsDir;
        this.projectsDir = projectsDir;
    }

    @Override
    public List<AgentSession> collect(SharedProcessData shared) {
        var sessions = new ArrayList<AgentSession>();

        if (!Files.isDirectory(sessionsDir)) return sessions;

        try (var stream = Files.list(sessionsDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !isSymlink(p))
                    .forEach(p -> {
                        var session = loadSession(p, shared);
                        if (session != null) sessions.add(session);
                    });
        } catch (IOException ignored) {}

        // Evict cache for sessions that no longer exist
        var activeIds = new HashSet<String>();
        sessions.forEach(s -> activeIds.add(s.getSessionId()));
        transcriptCache.keySet().retainAll(activeIds);

        sessions.sort(Comparator.comparingLong(AgentSession::getStartedAt).reversed());
        return sessions;
    }

    private AgentSession loadSession(Path path, SharedProcessData shared) {
        try {
            var root = MAPPER.readTree(path.toFile());
            int pid = root.get("pid").asInt();
            String sessionId = truncate(root.get("sessionId").asText(""), 256);
            String cwd = truncate(root.get("cwd").asText(""), 4096);
            long startedAt = root.get("startedAt").asLong();

            var procInfo = shared.processInfo().get(pid);
            String procCmd = procInfo != null ? procInfo.command() : null;
            boolean pidAlive = procCmd != null && ProcessUtil.cmdHasBinary(procCmd, "claude");

            // Skip --print sessions (abtop's own summary generation)
            if (procCmd != null && procCmd.contains("--print")) return null;
            if (!pidAlive) return null;

            String projectName = cwd.contains("/") ? cwd.substring(cwd.lastIndexOf('/') + 1) : "?";
            long memMb = procInfo != null ? procInfo.rssKb() / 1024 : 0;

            // Parse transcript
            Path transcriptPath = findTranscript(cwd, sessionId);
            if (transcriptPath != null) {
                updateTranscriptCache(sessionId, transcriptPath);
            }

            var cached = transcriptCache.getOrDefault(sessionId, new TranscriptResult());

            // Status detection
            SessionStatus status;
            long sinceActivity = java.time.Duration.between(cached.lastActivity, Instant.now()).toSeconds();
            if (sinceActivity < 30) {
                status = SessionStatus.WORKING;
            } else {
                boolean cpuActive = procInfo != null && procInfo.cpuPct() > 1.0;
                boolean hasActiveChild = ProcessUtil.hasActiveDescendant(
                        pid, shared.childrenMap(), shared.processInfo(), 5.0);
                status = (cpuActive || hasActiveChild) ? SessionStatus.WORKING : SessionStatus.WAITING;
            }

            // Context window
            long contextWindow = contextWindowForModel(cached.model, cached.maxContextTokens);
            double contextPercent = contextWindow > 0
                    ? (cached.lastContextTokens * 100.0 / contextWindow) : 0.0;

            // Current tasks
            List<String> currentTasks;
            if (!cached.currentTask.isEmpty()) {
                currentTasks = List.of(cached.currentTask);
            } else if (status == SessionStatus.WAITING) {
                currentTasks = List.of("waiting for input");
            } else {
                currentTasks = List.of("thinking...");
            }

            // Children
            var children = buildChildren(pid, shared);

            // Subagents
            Path projectDir = transcriptPath != null
                    ? transcriptPath.getParent()
                    : projectsDir.resolve(encodeCwdPath(cwd));
            var subagents = collectSubagents(projectDir.resolve(sessionId).resolve("subagents"));

            // Memory status
            Path memoryDir = projectDir.resolve("memory");
            int[] memStats = collectMemoryStatus(memoryDir);

            // Effort level
            String effort = readEffortLevel(cwd);

            var session = new AgentSession("claude", pid);
            session.setSessionId(sessionId);
            session.setCwd(cwd);
            session.setProjectName(projectName);
            session.setStartedAt(startedAt);
            session.setStatus(status);
            session.setModel(cached.model);
            session.setEffort(effort);
            session.setContextPercent(contextPercent);
            session.setTotalInputTokens(cached.totalInput);
            session.setTotalOutputTokens(cached.totalOutput);
            session.setTotalCacheRead(cached.totalCacheRead);
            session.setTotalCacheCreate(cached.totalCacheCreate);
            session.setTurnCount(cached.turnCount);
            session.setCurrentTasks(currentTasks);
            session.setMemMb(memMb);
            session.setVersion(cached.version);
            session.setGitBranch(cached.gitBranch);
            session.setTokenHistory(new ArrayList<>(cached.tokenHistory));
            session.setSubagents(subagents);
            session.setMemFileCount(memStats[0]);
            session.setMemLineCount(memStats[1]);
            session.setChildren(children);
            session.setInitialPrompt(cached.initialPrompt);
            session.setFirstAssistantText(cached.firstAssistantText);
            return session;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateTranscriptCache(String sessionId, Path transcriptPath) {
        var prev = transcriptCache.remove(sessionId);
        var identity = fileIdentity(transcriptPath);
        boolean identityChanged = prev != null
                && (prev.fileInode != identity[0] || prev.fileMtimeMillis != identity[1]);
        long fromOffset = identityChanged ? 0 : (prev != null ? prev.newOffset : 0);

        var delta = parseTranscript(transcriptPath, fromOffset);

        if (prev != null && !identityChanged && fromOffset > 0 && delta.newOffset >= fromOffset) {
            prev.mergeFrom(delta);
            transcriptCache.put(sessionId, prev);
        } else {
            transcriptCache.put(sessionId, delta);
        }
    }

    Path findTranscript(String cwd, String sessionId) {
        String jsonlName = sessionId + ".jsonl";

        // Primary: encoded cwd path
        Path primary = projectsDir.resolve(encodeCwdPath(cwd)).resolve(jsonlName);
        if (Files.exists(primary) && !isSymlink(primary)) return primary;

        // Fallback: scan all project directories
        try (var dirs = Files.list(projectsDir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> !isSymlink(d))
                    .map(d -> d.resolve(jsonlName))
                    .filter(Files::exists)
                    .filter(p -> !isSymlink(p))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // --- Transcript parsing (package-visible for testing) ---

    static TranscriptResult parseTranscript(Path path, long fromOffset) {
        var result = new TranscriptResult();
        var identity = fileIdentity(path);
        result.fileInode = identity[0];
        result.fileMtimeMillis = identity[1];
        result.newOffset = fromOffset;

        long fileLen;
        try {
            fileLen = Files.size(path);
        } catch (IOException e) {
            return result;
        }

        if (fileLen == fromOffset) {
            result.newOffset = fileLen;
            return result;
        }
        // File shrank — reset
        long effectiveOffset = fileLen < fromOffset ? 0 : fromOffset;

        result.lastActivity = fileLastModified(path);

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
                } catch (Exception e) {
                    continue; // skip malformed
                }

                String type = val.has("type") ? val.get("type").asText() : "";
                switch (type) {
                    case "assistant" -> parseAssistant(val, result);
                    case "user" -> parseUser(val, result);
                    default -> { /* ignore */ }
                }
            }
            result.newOffset = bytesRead;
        } catch (IOException ignored) {}

        return result;
    }

    private static void parseAssistant(JsonNode val, TranscriptResult result) {
        result.turnCount++;
        result.currentTask = ""; // clear previous

        var msg = val.get("message");
        if (msg == null) return;

        if (msg.has("model")) result.model = msg.get("model").asText();

        var usage = msg.get("usage");
        if (usage != null) {
            long inp = usage.path("input_tokens").asLong(0);
            long out = usage.path("output_tokens").asLong(0);
            long cr = usage.path("cache_read_input_tokens").asLong(0);
            long cc = usage.path("cache_creation_input_tokens").asLong(0);
            result.totalInput += inp;
            result.totalOutput += out;
            result.totalCacheRead += cr;
            result.totalCacheCreate += cc;
            result.lastContextTokens = inp + cr + cc;
            if (result.lastContextTokens > result.maxContextTokens) {
                result.maxContextTokens = result.lastContextTokens;
            }
            if (result.tokenHistory.size() < 10_000) {
                result.tokenHistory.add(inp + out + cr + cc);
            }
        }

        // First assistant text
        var content = msg.get("content");
        if (result.firstAssistantText.isEmpty() && content != null && content.isArray()) {
            var sb = new StringBuilder();
            for (var block : content) {
                if ("text".equals(block.path("type").asText())) {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(block.path("text").asText(""));
                }
            }
            if (!sb.isEmpty()) {
                String normalized = String.join(" ",
                        sb.toString().lines()
                                .map(String::trim)
                                .filter(l -> !l.isEmpty())
                                .toList());
                result.firstAssistantText = truncate(normalized, 200);
            }
        }

        // Last tool_use from this turn
        if (content != null && content.isArray()) {
            for (int i = content.size() - 1; i >= 0; i--) {
                var item = content.get(i);
                if ("tool_use".equals(item.path("type").asText())) {
                    String tool = item.path("name").asText("?");
                    String arg = extractToolArg(item);
                    result.currentTask = tool + " " + arg;
                    break;
                }
            }
        }
    }

    private static void parseUser(JsonNode val, TranscriptResult result) {
        if (val.has("version")) result.version = val.get("version").asText();
        if (val.has("gitBranch")) result.gitBranch = val.get("gitBranch").asText();

        if (result.initialPrompt.isEmpty()) {
            var msg = val.get("message");
            if (msg != null) {
                result.initialPrompt = extractPromptText(msg);
            }
        }
    }

    static String extractPromptText(JsonNode message) {
        var contentNode = message.get("content");
        String raw;
        if (contentNode == null) return "";
        if (contentNode.isTextual()) {
            raw = contentNode.asText();
        } else if (contentNode.isArray()) {
            raw = null;
            for (var block : contentNode) {
                if ("text".equals(block.path("type").asText())) {
                    raw = block.path("text").asText("");
                    break;
                }
            }
            if (raw == null) return "";
        } else {
            return "";
        }

        // Clean up
        var lines = raw.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith("```"))
                .toList();
        String cleaned = String.join(" ", lines);

        // Remove [Image #N] markers
        cleaned = cleaned.replaceAll("\\[Image[^]]*\\]\\s*", "");
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) return "";
        if (cleaned.contains("You are a conversation title generator")) return "";
        return truncate(cleaned, 50);
    }

    private static String extractToolArg(JsonNode toolUse) {
        var input = toolUse.get("input");
        if (input == null) return "";
        if (input.has("file_path")) return shortenPath(input.get("file_path").asText());
        if (input.has("command")) {
            String cmd = input.get("command").asText();
            String firstLine = cmd.lines().findFirst().orElse(cmd);
            return SecretRedactor.redact(truncate(firstLine, 40));
        }
        if (input.has("pattern")) return truncate(input.get("pattern").asText(), 40);
        return "";
    }

    // --- Subagents ---

    private List<SubAgent> collectSubagents(Path subagentsDir) {
        var subagents = new ArrayList<SubAgent>();
        if (!Files.isDirectory(subagentsDir)) return subagents;

        try (var stream = Files.list(subagentsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".meta.json"))
                    .filter(p -> !isSymlink(p))
                    .forEach(metaPath -> {
                        try {
                            var meta = MAPPER.readTree(metaPath.toFile());
                            String description = meta.path("description").asText("agent");

                            String jsonlName = metaPath.getFileName().toString()
                                    .replace(".meta.json", ".jsonl");
                            Path jsonlPath = metaPath.resolveSibling(jsonlName);

                            long tokens = 0;
                            String status = "done";
                            if (Files.exists(jsonlPath)) {
                                var lastMod = Files.getLastModifiedTime(jsonlPath).toInstant();
                                long sinceActivity = java.time.Duration.between(lastMod, Instant.now()).toSeconds();
                                status = sinceActivity < 30 ? "working" : "done";

                                var transcript = parseTranscript(jsonlPath, 0);
                                tokens = transcript.totalInput + transcript.totalOutput
                                        + transcript.totalCacheRead + transcript.totalCacheCreate;
                            }

                            subagents.add(new SubAgent(truncate(description, 30), status, tokens));
                        } catch (Exception ignored) {}
                    });
        } catch (IOException ignored) {}
        return subagents;
    }

    // --- Memory status ---

    private int[] collectMemoryStatus(Path memoryDir) {
        int fileCount = 0, lineCount = 0;
        if (Files.isDirectory(memoryDir)) {
            try (var stream = Files.list(memoryDir)) {
                fileCount = (int) stream.filter(Files::isRegularFile).count();
            } catch (IOException ignored) {}

            Path memoryMd = memoryDir.resolve("MEMORY.md");
            if (Files.exists(memoryMd)) {
                try {
                    lineCount = (int) Files.lines(memoryMd).count();
                } catch (IOException ignored) {}
            }
        }
        return new int[]{fileCount, lineCount};
    }

    // --- Effort level ---

    static String readEffortLevel(String cwd) {
        String envVal = System.getenv("CLAUDE_CODE_EFFORT_LEVEL");
        if (envVal != null && !envVal.trim().isEmpty()) return envVal.trim();

        var candidates = new ArrayList<Path>();
        Path cwdPath = Path.of(cwd);
        candidates.add(cwdPath.resolve(".claude/settings.local.json"));
        candidates.add(cwdPath.resolve(".claude/settings.json"));
        String home = System.getProperty("user.home");
        if (home != null) {
            candidates.add(Path.of(home, ".claude", "settings.local.json"));
            candidates.add(Path.of(home, ".claude", "settings.json"));
        }

        for (Path p : candidates) {
            String level = readEffortFromSettings(p);
            if (level != null) return level;
        }
        return "";
    }

    static String readEffortFromSettings(Path path) {
        try {
            if (!Files.exists(path)) return null;
            var root = MAPPER.readTree(path.toFile());
            var node = root.get("effortLevel");
            if (node == null || !node.isTextual()) return null;
            String val = node.asText().trim();
            return val.isEmpty() ? null : val;
        } catch (Exception e) {
            return null;
        }
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

    // --- Utility ---

    /** Encode a cwd path: replace /, _, . with - */
    static String encodeCwdPath(String cwd) {
        var sb = new StringBuilder(cwd.length());
        for (char c : cwd.toCharArray()) {
            sb.append(c == '/' || c == '_' || c == '.' ? '-' : c);
        }
        return sb.toString();
    }

    static long contextWindowForModel(String model, long maxContextTokens) {
        if (model.contains("[1m]") || maxContextTokens > 200_000) return 1_000_000;
        return 200_000;
    }

    static String truncate(String s, int max) {
        if (max == 0) return "";
        if (s.codePointCount(0, s.length()) <= max) return s;
        int end = s.offsetByCodePoints(0, max - 1);
        return s.substring(0, end) + "…";
    }

    static String shortenPath(String path) {
        String[] parts = path.split("/");
        if (parts.length <= 2) return path;
        return parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private static boolean isSymlink(Path path) {
        return Files.isSymbolicLink(path);
    }

    private static long[] fileIdentity(Path path) {
        try {
            long ino = ((Number) Files.getAttribute(path, "unix:ino")).longValue();
            long mtime = Files.getLastModifiedTime(path).toMillis();
            return new long[]{ino, mtime};
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    private static Instant fileLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    /**
     * Read a line from RandomAccessFile, bounded to maxBytes.
     * Returns null at EOF. Handles multi-byte UTF-8 correctly.
     */
    private static String readLineBounded(RandomAccessFile raf, int maxBytes) throws IOException {
        var buf = new java.io.ByteArrayOutputStream();
        int b;
        int count = 0;
        while ((b = raf.read()) != -1) {
            count++;
            if (b == '\n') return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (count > maxBytes) {
                // Skip to EOF — malformed line
                raf.seek(raf.length());
                return null;
            }
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Path claudeBaseDir() {
        String envDir = System.getenv("CLAUDE_CONFIG_DIR");
        if (envDir != null) {
            Path p = Path.of(envDir);
            if (Files.isDirectory(p)) return p;
        }
        return Path.of(System.getProperty("user.home", ""), ".claude");
    }
}
