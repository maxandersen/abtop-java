package dev.abtop.collector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed state from a Claude Code transcript JSONL file.
 * Tracks cumulative totals and supports incremental (offset-based) parsing.
 */
class TranscriptResult {
    String model = "-";
    long totalInput;
    long totalOutput;
    long totalCacheRead;
    long totalCacheCreate;
    long lastContextTokens;
    long maxContextTokens;
    int turnCount;
    String currentTask = "";
    String version = "";
    String gitBranch = "";
    Instant lastActivity = Instant.EPOCH;
    long newOffset;
    /** File identity: inode + mtime millis. Used to detect file replacement. */
    long fileInode;
    long fileMtimeMillis;
    List<Long> tokenHistory = new ArrayList<>();
    String initialPrompt = "";
    String firstAssistantText = "";

    /** Merge a delta (from incremental parse) into this result. */
    void mergeFrom(TranscriptResult delta) {
        if (!"-".equals(delta.model)) this.model = delta.model;
        this.totalInput += delta.totalInput;
        this.totalOutput += delta.totalOutput;
        this.totalCacheRead += delta.totalCacheRead;
        this.totalCacheCreate += delta.totalCacheCreate;
        if (delta.lastContextTokens > 0) this.lastContextTokens = delta.lastContextTokens;
        if (delta.maxContextTokens > this.maxContextTokens) this.maxContextTokens = delta.maxContextTokens;
        this.turnCount += delta.turnCount;
        // Always update current_task from delta — empty means latest turn had no tool_use
        if (delta.turnCount > 0) this.currentTask = delta.currentTask;
        if (!delta.version.isEmpty()) this.version = delta.version;
        if (!delta.gitBranch.isEmpty()) this.gitBranch = delta.gitBranch;
        if (delta.lastActivity.isAfter(this.lastActivity)) this.lastActivity = delta.lastActivity;
        this.tokenHistory.addAll(delta.tokenHistory);
        if (this.initialPrompt.isEmpty() && !delta.initialPrompt.isEmpty()) {
            this.initialPrompt = delta.initialPrompt;
        }
        this.newOffset = delta.newOffset;
    }

    boolean identityChanged(TranscriptResult other) {
        return this.fileInode != other.fileInode || this.fileMtimeMillis != other.fileMtimeMillis;
    }
}
