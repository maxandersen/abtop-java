package dev.abtop.collector;

import dev.abtop.model.AgentSession;
import dev.abtop.model.RateLimitInfo;

import java.util.List;
import java.util.Optional;

/**
 * Interface for agent-specific session collectors.
 * Implement this to add support for a new AI coding agent.
 */
public interface AgentCollector {

    /** Return all live sessions for this agent type. */
    List<AgentSession> collect(SharedProcessData shared);

    /** Return agent-specific rate limit info, if available from session data. */
    default Optional<RateLimitInfo> liveRateLimit() {
        return Optional.empty();
    }
}
