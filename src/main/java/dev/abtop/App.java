package dev.abtop;

import dev.abtop.collector.MultiCollector;
import dev.abtop.collector.ProcessUtil;
import dev.abtop.collector.RateLimitReader;
import dev.abtop.model.*;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Application state: sessions, token rates, rate limits, summaries.
 * Tick-driven: call tick() every 2s to refresh data.
 */
public class App {

    private static final int GRAPH_HISTORY_LEN = 200;
    private static final int MAX_SUMMARY_JOBS = 3;
    private static final int MAX_SUMMARY_RETRIES = 2;

    // --- State ---
    private List<AgentSession> sessions = new ArrayList<>();
    private int selected;
    private boolean shouldQuit;
    private final ArrayDeque<Double> tokenRates = new ArrayDeque<>();
    private List<RateLimitInfo> rateLimits = new ArrayList<>();
    private final Map<String, Long> prevTokens = new HashMap<>();
    private int rateLimitCounter = 5; // trigger on first tick
    private final MultiCollector collector = new MultiCollector();
    private final Map<String, String> summaries;
    private final Set<String> pendingSummaries = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> summaryRetries = new ConcurrentHashMap<>();
    private final ExecutorService summaryExecutor = Executors.newFixedThreadPool(MAX_SUMMARY_JOBS);
    private final BlockingQueue<SummaryResult> summaryResults = new LinkedBlockingQueue<>();
    private List<OrphanPort> orphanPorts = new ArrayList<>();
    private String statusMsg;
    private Instant statusMsgTime;
    private int[] killConfirm; // [selectedIndex, epochSecond]
    private Theme theme;
    private Instant lastTick = Instant.EPOCH;

    public App(Theme theme) {
        this.theme = theme;
        this.summaries = loadSummaryCache();
    }

    // --- Public accessors ---
    public List<AgentSession> getSessions() { return sessions; }
    public int getSelected() { return selected; }
    public boolean isShouldQuit() { return shouldQuit; }
    public ArrayDeque<Double> getTokenRates() { return tokenRates; }
    public List<RateLimitInfo> getRateLimits() { return rateLimits; }
    public List<OrphanPort> getOrphanPorts() { return orphanPorts; }
    public Theme getTheme() { return theme; }

    public String getStatusMsg() {
        if (statusMsg != null && statusMsgTime != null) {
            if (Instant.now().getEpochSecond() - statusMsgTime.getEpochSecond() > 5) {
                statusMsg = null;
                statusMsgTime = null;
            }
        }
        return statusMsg;
    }

    public boolean shouldTick() {
        return java.time.Duration.between(lastTick, Instant.now()).toMillis() >= 2000;
    }

    // --- Actions ---

    public void tick() {
        lastTick = Instant.now();
        sessions = collector.collect();
        orphanPorts = collector.getOrphanPorts();
        if (selected >= sessions.size() && !sessions.isEmpty()) {
            selected = sessions.size() - 1;
        }

        // Compute rate
        double rate = 0;
        for (var s : sessions) {
            String key = s.getAgentCli() + ":" + s.getSessionId();
            long total = s.activeTokens();
            long prev = prevTokens.getOrDefault(key, total);
            rate += Math.max(0, total - prev);
            prevTokens.put(key, total);
        }
        tokenRates.addLast(rate);
        while (tokenRates.size() > GRAPH_HISTORY_LEN) tokenRates.pollFirst();

        // Rate limits
        if (rateLimits.isEmpty() || rateLimitCounter >= 5) {
            rateLimitCounter = 0;
            rateLimits = new ArrayList<>(RateLimitReader.readRateLimits());
            rateLimits.addAll(collector.agentRateLimits());
        } else {
            rateLimitCounter++;
        }

        drainAndRetrySummaries();
    }

    public void drainAndRetrySummaries() {
        SummaryResult result;
        while ((result = summaryResults.poll()) != null) {
            pendingSummaries.remove(result.sessionId);
            if (result.summary != null) {
                summaryRetries.remove(result.sessionId);
                summaries.put(result.sessionId, result.summary);
                saveSummaryCache(summaries);
            } else {
                int count = summaryRetries.merge(result.sessionId, 1, Integer::sum);
                if (count >= MAX_SUMMARY_RETRIES) {
                    summaries.put(result.sessionId, sanitizeFallback(result.fallbackPrompt, 28));
                    saveSummaryCache(summaries);
                }
            }
        }

        // Spawn new summary jobs
        for (var s : sessions) {
            int retries = summaryRetries.getOrDefault(s.getSessionId(), 0);
            boolean hasInput = !s.getInitialPrompt().isEmpty() || !s.getFirstAssistantText().isEmpty();
            if (hasInput && !summaries.containsKey(s.getSessionId())
                    && !pendingSummaries.contains(s.getSessionId())
                    && pendingSummaries.size() < MAX_SUMMARY_JOBS
                    && retries < MAX_SUMMARY_RETRIES) {
                pendingSummaries.add(s.getSessionId());
                String sid = s.getSessionId();
                String prompt = s.getInitialPrompt();
                String assistantText = s.getFirstAssistantText();
                summaryExecutor.submit(() -> {
                    String summary = generateSummary(prompt, assistantText);
                    String fallback = prompt.isEmpty() ? assistantText : prompt;
                    summaryResults.offer(new SummaryResult(sid, fallback, summary));
                });
            }
        }
    }

    public boolean hasPendingSummaries() { return !pendingSummaries.isEmpty(); }

    public boolean hasRetryableSummaries() {
        return sessions.stream().anyMatch(s ->
                (!s.getInitialPrompt().isEmpty() || !s.getFirstAssistantText().isEmpty())
                        && !summaries.containsKey(s.getSessionId())
                        && !pendingSummaries.contains(s.getSessionId())
                        && summaryRetries.getOrDefault(s.getSessionId(), 0) < MAX_SUMMARY_RETRIES
        );
    }

    public String sessionSummary(AgentSession session) {
        var cached = summaries.get(session.getSessionId());
        if (cached != null) return cached;

        if (session.getStatus() == SessionStatus.DONE) {
            if (!session.getInitialPrompt().isEmpty()) return sanitizeFallback(session.getInitialPrompt(), 28);
            if (!session.getFirstAssistantText().isEmpty()) return sanitizeFallback(session.getFirstAssistantText(), 28);
            return "—";
        }

        if (pendingSummaries.contains(session.getSessionId())) {
            int dots = (int) ((System.currentTimeMillis() / 500) % 3);
            return ".".repeat(dots + 1);
        }

        if (!session.getInitialPrompt().isEmpty()) return sanitizeFallback(session.getInitialPrompt(), 28);
        if (!session.getFirstAssistantText().isEmpty()) return sanitizeFallback(session.getFirstAssistantText(), 28);
        return "—";
    }

    public void selectNext() {
        if (!sessions.isEmpty()) selected = Math.min(selected + 1, sessions.size() - 1);
    }

    public void selectPrev() {
        selected = Math.max(0, selected - 1);
    }

    public void quit() { shouldQuit = true; }

    public void setStatus(String msg) {
        this.statusMsg = msg;
        this.statusMsgTime = Instant.now();
    }

    public void cycleTheme() {
        var names = Theme.THEME_NAMES;
        int current = 0;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(theme.name())) { current = i; break; }
        }
        int next = (current + 1) % names.length;
        theme = Theme.byName(names[next]);
        if (theme == null) theme = Theme.defaultTheme();
        try {
            Config.saveTheme(names[next]);
            setStatus("theme: " + names[next]);
        } catch (Exception e) {
            setStatus("theme: " + names[next] + " (save failed: " + e.getMessage() + ")");
        }
    }

    public void killSelected() {
        if (sessions.isEmpty()) return;
        var session = sessions.get(selected);
        if (session.getStatus() == SessionStatus.DONE) return;

        if (killConfirm != null && killConfirm[0] == selected
                && Instant.now().getEpochSecond() - killConfirm[1] < 2) {
            killConfirm = null;
            int pid = session.getPid();
            // Verify PID is still a known agent using ps command output
            // (ProcessHandle.info().command() returns the interpreter e.g. "node",
            // not the script name "pi", so we use ps instead)
            String psCmd = ProcessUtil.getCommandForPid(pid);
            if (psCmd != null && isAgentCommand(psCmd)) {
                ProcessHandle.of(pid).ifPresentOrElse(
                        ProcessHandle::destroyForcibly,
                        () -> setStatus("PID " + pid + " already exited")
                );
                tick();
            } else if (psCmd == null) {
                setStatus("PID " + pid + " not found");
            } else {
                setStatus("PID " + pid + " is not a known agent process");
            }
            return;
        }

        String name = summaries.getOrDefault(session.getSessionId(), "PID " + session.getPid());
        killConfirm = new int[]{selected, (int) Instant.now().getEpochSecond()};
        setStatus("Press x again to kill: " + name);
    }

    public void killOrphanPorts() {
        var freshPorts = ProcessUtil.getListeningPorts();
        for (var orphan : orphanPorts) {
            var ports = freshPorts.getOrDefault(orphan.pid(), List.of());
            if (!ports.contains(orphan.port())) continue;
            ProcessHandle.of(orphan.pid()).ifPresent(ph -> {
                var cmd = ph.info().commandLine().orElse("");
                if (cmd.equals(orphan.command())) {
                    ph.destroy(); // SIGTERM, not SIGKILL
                }
            });
        }
        tick();
    }

    public String jumpToSession() {
        if (sessions.isEmpty()) return "no sessions";
        var session = sessions.get(selected);
        int pid = session.getPid();
        if (pid <= 0) return "no PID for session";

        // Try zellij first (check env, then fall back to command existence)
        if (System.getenv("ZELLIJ_SESSION_NAME") != null) {
            setStatus("jumping via zellij to PID " + pid + "...");
            var result = jumpViaZellij(pid);
            return result; // null = success, string = error
        }
        // Try tmux — only if abtop is running inside tmux
        if (System.getenv("TMUX") != null) {
            var result = jumpViaTmux(pid);
            return result != null
                    ? "PID " + pid + " not in any tmux pane"
                    : null;
        }
        return "not in tmux/zellij — can't jump to pane";
    }

    private static boolean tmuxServerRunning() {
        try {
            var proc = new ProcessBuilder("tmux", "list-sessions")
                    .redirectErrorStream(true).start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private String jumpViaTmux(int targetPid) {
        try {
            var pb = new ProcessBuilder("tmux", "list-panes", "-a", "-F",
                    "#{pane_pid} #{session_name}:#{window_index}.#{pane_index}");
            var proc = pb.start();
            var output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();

            for (String line : output.lines().toList()) {
                var parts = line.split(" ", 2);
                if (parts.length < 2) continue;
                try {
                    int panePid = Integer.parseInt(parts[0]);
                    String paneTarget = parts[1];
                    if (ProcessUtil.isDescendantOf(targetPid, panePid)) {
                        String sessionName = paneTarget.split(":")[0];
                        String window = paneTarget.split("\\.")[0];
                        new ProcessBuilder("tmux", "switch-client", "-t", sessionName).start().waitFor();
                        new ProcessBuilder("tmux", "select-window", "-t", window).start().waitFor();
                        new ProcessBuilder("tmux", "select-pane", "-t", paneTarget).start().waitFor();
                        return null;
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return "pane not found";
    }

    /**
     * Jump to a pane in zellij by matching agent PID to pane shell PIDs.
     * Uses: zellij action list-panes --json --command
     * Output is a JSON array of objects with "pane_id", "shell_pid", etc.
     */
    private String jumpViaZellij(int targetPid) {
        var session = sessions.isEmpty() ? null : sessions.get(selected);
        String targetCwd = session != null ? session.getCwd() : "";
        String targetAgent = session != null ? session.getAgentCli() : "";

        try {
            // Scan all active zellij sessions, not just the current one
            var sessionNames = listZellijSessions();
            if (sessionNames.isEmpty()) return "no zellij sessions found";

            var mapper = dev.abtop.collector.Json.MAPPER;
            String currentSession = System.getenv("ZELLIJ_SESSION_NAME");

            for (String zellijSession : sessionNames) {
                String output = zellijListPanes(zellijSession);
                if (output == null || !output.startsWith("[")) continue;

                var panes = mapper.readTree(output);
                for (var pane : panes) {
                    if (pane.path("is_plugin").asBoolean(false)) continue;

                    String paneCmd = pane.path("pane_command").asText("");
                    String paneCwd = pane.path("pane_cwd").asText("");

                    boolean cmdMatch = !paneCmd.isEmpty() && (
                            paneCmd.equals(targetAgent)
                            || ProcessUtil.cmdHasBinary(paneCmd, targetAgent)
                            || ProcessUtil.cmdHasBinary(paneCmd, "claude")
                            || ProcessUtil.cmdHasBinary(paneCmd, "codex")
                            || ProcessUtil.cmdHasBinary(paneCmd, "pi")
                            || ProcessUtil.cmdHasBinary(paneCmd, "opencode"));
                    boolean cwdMatch = !paneCwd.isEmpty() && paneCwd.equals(targetCwd);

                    if (cmdMatch && cwdMatch) {
                        int id = pane.path("id").asInt(-1);
                        if (id < 0) continue;
                        String paneId = "terminal_" + id;

                        if (!zellijSession.equals(currentSession)) {
                            // Focus the pane in the remote session, then switch to it
                            new ProcessBuilder("zellij", "--session", zellijSession,
                                    "action", "focus-pane-id", paneId)
                                    .start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                            new ProcessBuilder("zellij", "action", "switch-session", zellijSession)
                                    .start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                            return null;
                        }

                        int tabPosition = pane.path("tab_position").asInt(-1);
                        if (tabPosition >= 0) {
                            new ProcessBuilder("zellij", "action", "go-to-tab", String.valueOf(tabPosition + 1))
                                    .start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                        }
                        new ProcessBuilder("zellij", "action", "focus-pane-id", paneId)
                                .start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                        return null;
                    }
                }
            }
        } catch (Exception e) {
            return "zellij error: " + e.getMessage();
        }
        return "PID " + targetPid + " not in any zellij pane";
    }

    private static List<String> listZellijSessions() {
        var names = new java.util.ArrayList<String>();
        try {
            var proc = new ProcessBuilder("zellij", "list-sessions", "--no-formatting")
                    .redirectErrorStream(true).start();
            var output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            for (String line : output.lines().toList()) {
                if (line.contains("EXITED")) continue;
                // Format: "session-name [Created ...] (current)"
                int bracket = line.indexOf('[');
                String name = (bracket > 0 ? line.substring(0, bracket) : line).trim();
                if (!name.isEmpty()) names.add(name);
            }
        } catch (Exception ignored) {}
        return names;
    }

    private static String zellijListPanes(String sessionName) {
        try {
            var pb = new ProcessBuilder("zellij", "--session", sessionName,
                    "action", "list-panes", "--json", "--command");
            pb.redirectErrorStream(false);
            var proc = pb.start();
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return new String(proc.getInputStream().readAllBytes()).trim(); }
                catch (Exception e) { return ""; }
            });
            String output = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
            return output.isEmpty() ? null : output;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAgentCommand(String cmd) {
        return ProcessUtil.cmdHasBinary(cmd, "claude")
                || ProcessUtil.cmdHasBinary(cmd, "codex")
                || ProcessUtil.cmdHasBinary(cmd, "pi")
                || ProcessUtil.cmdHasBinary(cmd, "opencode");
    }

    public void shutdown() {
        summaryExecutor.shutdownNow();
    }

    // --- Summary generation ---

    private String generateSummary(String prompt, String assistantText) {
        String userPart = prompt.length() > 200 ? prompt.substring(0, 200) : prompt;
        String assistPart = assistantText.length() > 200 ? assistantText.substring(0, 200) : assistantText;

        String context;
        if (!userPart.isEmpty() && !assistPart.isEmpty()) {
            context = "User message: " + userPart + "\n\nAssistant response: " + assistPart;
        } else if (!assistPart.isEmpty()) {
            context = "Assistant response: " + assistPart;
        } else {
            context = "User message: " + userPart;
        }

        String request = "You are a conversation title generator. Given the conversation below, " +
                "create a short title (3-5 words) that describes the session's main topic. " +
                "Be specific and actionable. Do NOT output generic titles like 'New conversation' " +
                "or 'Initial setup'. Output ONLY the title, no quotes, no explanation.\n\n" + context;

        try {
            var pb = new ProcessBuilder("claude", "--print", "-");
            pb.directory(Path.of(System.getProperty("java.io.tmpdir")).toFile());
            pb.redirectErrorStream(false);
            var proc = pb.start();
            proc.getOutputStream().write(request.getBytes());
            proc.getOutputStream().close();

            boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null; // retry
            }

            String raw = new String(proc.getInputStream().readAllBytes()).trim();
            String lower = raw.toLowerCase();

            if (raw.isEmpty() || raw.codePointCount(0, raw.length()) > 40
                    || raw.contains("Summarize") || raw.startsWith("- ")
                    || lower.contains("new conversation") || lower.contains("initial setup")
                    || lower.contains("initial project") || lower.contains("initial conversation")
                    || lower.startsWith("greeting")) {
                return sanitizeFallback(prompt, 28);
            }

            String result = raw;
            if (result.startsWith("\"")) result = result.substring(1);
            if (result.endsWith("\"")) result = result.substring(0, result.length() - 1);
            if (result.startsWith("'")) result = result.substring(1);
            if (result.endsWith("'")) result = result.substring(0, result.length() - 1);
            return result;
        } catch (Exception e) {
            return sanitizeFallback(prompt, 28);
        }
    }

    static String sanitizeFallback(String prompt, int maxLen) {
        if (prompt == null || prompt.isEmpty()) return "—";
        var cleaned = new StringBuilder();
        int count = 0;
        for (int i = 0; i < prompt.length() && count < maxLen; ) {
            int cp = prompt.codePointAt(i);
            if (!Character.isISOControl(cp) || cp == ' ') {
                cleaned.appendCodePoint(cp);
                count++;
            }
            i += Character.charCount(cp);
        }
        if (prompt.codePointCount(0, prompt.length()) > maxLen) {
            return cleaned + "…";
        }
        return cleaned.toString();
    }

    // --- Summary cache ---

    private static Path cachePath() {
        String home = System.getProperty("user.home");
        if (home == null) return null;
        String os = System.getProperty("os.name", "").toLowerCase();
        Path cacheDir;
        if (os.contains("mac")) {
            cacheDir = Path.of(home, "Library", "Caches");
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            cacheDir = xdg != null ? Path.of(xdg) : Path.of(home, ".cache");
        }
        return cacheDir.resolve("abtop").resolve("summaries.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> loadSummaryCache() {
        var path = cachePath();
        if (path == null || !Files.exists(path)) return new ConcurrentHashMap<>();
        try {
            var mapper = dev.abtop.collector.Json.MAPPER;
            Map<String, String> cache = mapper.readValue(path.toFile(), Map.class);
            // Purge polluted entries
            cache.values().removeIf(v -> v.contains("You are a conversation tit"));
            return new ConcurrentHashMap<>(cache);
        } catch (Exception e) {
            return new ConcurrentHashMap<>();
        }
    }

    private static void saveSummaryCache(Map<String, String> summaries) {
        var path = cachePath();
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent());
            var mapper = dev.abtop.collector.Json.MAPPER;
            var tmp = path.resolveSibling("summaries.json.tmp");
            mapper.writeValue(tmp.toFile(), summaries);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {}
    }

    private record SummaryResult(String sessionId, String fallbackPrompt, String summary) {}
}
