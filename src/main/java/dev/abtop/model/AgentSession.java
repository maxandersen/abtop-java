package dev.abtop.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single AI agent session (Claude Code, Codex CLI, pi, etc.).
 * Mutable — fields updated each tick by collectors.
 */
public class AgentSession {

    private String agentCli;       // "claude", "codex", "pi"
    private int pid;
    private String sessionId = "";
    private String cwd = "";
    private String projectName = "";
    private long startedAt;        // epoch millis
    private SessionStatus status = SessionStatus.WAITING;
    private String model = "";
    private String effort = "";    // "minimal"|"low"|"medium"|"high" or ""
    private double contextPercent;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalCacheRead;
    private long totalCacheCreate;
    private int turnCount;
    private List<String> currentTasks = new ArrayList<>();
    private long memMb;
    private String version = "";
    private String gitBranch = "";
    private int gitAdded;
    private int gitModified;
    private List<Long> tokenHistory = new ArrayList<>();
    private List<SubAgent> subagents = new ArrayList<>();
    private int memFileCount;
    private int memLineCount;
    private List<ChildProcess> children = new ArrayList<>();
    private String initialPrompt = "";
    private String firstAssistantText = "";

    public AgentSession(String agentCli, int pid) {
        this.agentCli = agentCli;
        this.pid = pid;
    }

    /** Total tokens across all categories. */
    public long totalTokens() {
        return totalInputTokens + totalOutputTokens + totalCacheRead + totalCacheCreate;
    }

    /** Tokens representing new work (excludes cache reads). Used for rate calculation. */
    public long activeTokens() {
        return totalInputTokens + totalOutputTokens + totalCacheCreate;
    }

    public Duration elapsed() {
        long now = Instant.now().toEpochMilli();
        return Duration.ofMillis(Math.max(0, now - startedAt));
    }

    public String elapsedDisplay() {
        long secs = elapsed().toSeconds();
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
    }

    // --- Getters and setters ---

    public String getAgentCli() { return agentCli; }
    public void setAgentCli(String agentCli) { this.agentCli = agentCli; }

    public int getPid() { return pid; }
    public void setPid(int pid) { this.pid = pid; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }

    public double getContextPercent() { return contextPercent; }
    public void setContextPercent(double contextPercent) { this.contextPercent = contextPercent; }

    public long getTotalInputTokens() { return totalInputTokens; }
    public void setTotalInputTokens(long v) { this.totalInputTokens = v; }

    public long getTotalOutputTokens() { return totalOutputTokens; }
    public void setTotalOutputTokens(long v) { this.totalOutputTokens = v; }

    public long getTotalCacheRead() { return totalCacheRead; }
    public void setTotalCacheRead(long v) { this.totalCacheRead = v; }

    public long getTotalCacheCreate() { return totalCacheCreate; }
    public void setTotalCacheCreate(long v) { this.totalCacheCreate = v; }

    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }

    public List<String> getCurrentTasks() { return currentTasks; }
    public void setCurrentTasks(List<String> currentTasks) { this.currentTasks = currentTasks; }

    public long getMemMb() { return memMb; }
    public void setMemMb(long memMb) { this.memMb = memMb; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getGitBranch() { return gitBranch; }
    public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }

    public int getGitAdded() { return gitAdded; }
    public void setGitAdded(int gitAdded) { this.gitAdded = gitAdded; }

    public int getGitModified() { return gitModified; }
    public void setGitModified(int gitModified) { this.gitModified = gitModified; }

    public List<Long> getTokenHistory() { return tokenHistory; }
    public void setTokenHistory(List<Long> tokenHistory) { this.tokenHistory = tokenHistory; }

    public List<SubAgent> getSubagents() { return subagents; }
    public void setSubagents(List<SubAgent> subagents) { this.subagents = subagents; }

    public int getMemFileCount() { return memFileCount; }
    public void setMemFileCount(int memFileCount) { this.memFileCount = memFileCount; }

    public int getMemLineCount() { return memLineCount; }
    public void setMemLineCount(int memLineCount) { this.memLineCount = memLineCount; }

    public List<ChildProcess> getChildren() { return children; }
    public void setChildren(List<ChildProcess> children) { this.children = children; }

    public String getInitialPrompt() { return initialPrompt; }
    public void setInitialPrompt(String initialPrompt) { this.initialPrompt = initialPrompt; }

    public String getFirstAssistantText() { return firstAssistantText; }
    public void setFirstAssistantText(String firstAssistantText) { this.firstAssistantText = firstAssistantText; }
}
