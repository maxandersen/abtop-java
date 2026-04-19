package dev.abtop.collector;

import dev.abtop.model.*;

import java.util.*;

/**
 * Aggregates sessions from multiple collectors (Claude, Codex, Pi, etc.).
 * Handles git stats caching, orphan port detection, and slow-poll scheduling.
 */
public class MultiCollector {

    /** How often to refresh expensive I/O (in ticks). 5 ticks × 2s = 10s. */
    private static final int SLOW_POLL_INTERVAL = 5;

    private final List<AgentCollector> collectors;
    private int tickCount = SLOW_POLL_INTERVAL; // trigger on first tick
    private Map<Integer, List<Integer>> cachedPorts = new HashMap<>();
    private List<Integer> cachedPortPids = new ArrayList<>();
    private final Map<String, int[]> cachedGit = new HashMap<>();

    /** Port-owning children from previous ticks, keyed by child PID. */
    private final Map<Integer, TrackedPortChild> trackedPortChildren = new HashMap<>();
    private List<OrphanPort> orphanPorts = new ArrayList<>();

    public MultiCollector() {
        this.collectors = new ArrayList<>(List.of(
                new ClaudeCollector(),
                new CodexCollector(),
                new PiCollector(),
                new OpencodeCollector()
        ));
    }

    /** Add a collector (e.g. PiCollector). */
    public void addCollector(AgentCollector collector) {
        collectors.add(collector);
    }

    public List<OrphanPort> getOrphanPorts() { return orphanPorts; }

    /** Collect rate limit info from all registered collectors. */
    public List<RateLimitInfo> agentRateLimits() {
        return collectors.stream()
                .map(AgentCollector::liveRateLimit)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    public List<AgentSession> collect() {
        boolean slowTick = tickCount >= SLOW_POLL_INTERVAL;
        if (slowTick) tickCount = 0;
        tickCount++;

        // Single ps scan, conditionally refresh ports
        var processInfo = ProcessUtil.getProcessInfo();
        var childrenMap = ProcessUtil.getChildrenMap(processInfo);
        var currentPids = new ArrayList<>(processInfo.keySet());
        Collections.sort(currentPids);
        boolean pidsChanged = !currentPids.equals(cachedPortPids);

        Map<Integer, List<Integer>> ports;
        if (slowTick || pidsChanged) {
            ports = ProcessUtil.getListeningPorts();
            cachedPorts = new HashMap<>(ports);
            cachedPortPids = currentPids;
        } else {
            ports = cachedPorts;
        }
        var shared = new SharedProcessData(processInfo, childrenMap, ports);

        var all = new ArrayList<AgentSession>();
        for (var collector : collectors) {
            all.addAll(collector.collect(shared));
        }

        // Git stats
        if (slowTick) {
            cachedGit.clear();
            for (var s : all) {
                var stats = ProcessUtil.collectGitStats(s.getCwd());
                cachedGit.put(s.getCwd(), stats);
                s.setGitAdded(stats[0]);
                s.setGitModified(stats[1]);
            }
        } else {
            for (var s : all) {
                var stats = cachedGit.get(s.getCwd());
                if (stats == null) {
                    stats = ProcessUtil.collectGitStats(s.getCwd());
                    cachedGit.put(s.getCwd(), stats);
                }
                s.setGitAdded(stats[0]);
                s.setGitModified(stats[1]);
            }
        }

        // Remove dead sessions
        all.removeIf(s -> s.getStatus() == SessionStatus.DONE);
        all.sort(Comparator.comparingLong(AgentSession::getStartedAt).reversed());

        // --- Orphan port detection ---
        var liveChildPids = new HashSet<Integer>();
        for (var s : all) {
            if (s.getStatus() != SessionStatus.DONE) {
                for (var child : s.getChildren()) {
                    liveChildPids.add(child.pid());
                    if (child.port() != null) {
                        trackedPortChildren.put(child.pid(), new TrackedPortChild(
                                child.port(), child.command(), s.getProjectName()));
                    }
                }
            }
        }

        orphanPorts = new ArrayList<>();
        var stalePids = new ArrayList<Integer>();
        for (var entry : trackedPortChildren.entrySet()) {
            int pid = entry.getKey();
            var tracked = entry.getValue();
            if (liveChildPids.contains(pid)) continue;

            var pidPorts = shared.ports().getOrDefault(pid, List.of());
            boolean stillListening = pidPorts.contains(tracked.port);
            boolean stillAlive = shared.processInfo().containsKey(pid);
            if (stillAlive && stillListening) {
                orphanPorts.add(new OrphanPort(tracked.port, pid, tracked.command, tracked.projectName));
            } else {
                stalePids.add(pid);
            }
        }
        stalePids.forEach(trackedPortChildren::remove);
        orphanPorts.sort(Comparator.comparingInt(OrphanPort::port));

        return all;
    }

    private record TrackedPortChild(int port, String command, String projectName) {}
}
