package dev.abtop.collector;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Process tree, port scanning, and git utilities.
 * All data sourced from ps, lsof, and git commands — no API calls.
 */
public final class ProcessUtil {

    private ProcessUtil() {}

    /** Process info parsed from ps output. */
    public record ProcInfo(int pid, int ppid, long rssKb, double cpuPct, String command) {}

    /** Parse all processes via: ps -ww -eo pid,ppid,rss,%cpu,command */
    public static Map<Integer, ProcInfo> getProcessInfo() {
        var map = new HashMap<Integer, ProcInfo>();
        try {
            var pb = new ProcessBuilder("ps", "-ww", "-eo", "pid,ppid,rss,%cpu,command");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                reader.readLine(); // skip header
                String line;
                while ((line = reader.readLine()) != null) {
                    var parts = line.trim().split("\\s+", 5);
                    if (parts.length >= 5) {
                        try {
                            int pid = Integer.parseInt(parts[0]);
                            int ppid = Integer.parseInt(parts[1]);
                            long rss = Long.parseLong(parts[2]);
                            double cpu = Double.parseDouble(parts[3]);
                            String cmd = parts[4];
                            map.put(pid, new ProcInfo(pid, ppid, rss, cpu, cmd));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (Exception ignored) {}
        return map;
    }

    /** Build parent → children map from process info. */
    public static Map<Integer, List<Integer>> getChildrenMap(Map<Integer, ProcInfo> procs) {
        var children = new HashMap<Integer, List<Integer>>();
        for (var info : procs.values()) {
            children.computeIfAbsent(info.ppid(), k -> new ArrayList<>()).add(info.pid());
        }
        return children;
    }

    /** Check if any descendant of pid has CPU usage above threshold. */
    public static boolean hasActiveDescendant(int pid, Map<Integer, List<Integer>> childrenMap,
                                               Map<Integer, ProcInfo> processInfo, double cpuThreshold) {
        var stack = new ArrayDeque<Integer>();
        stack.push(pid);
        var visited = new HashSet<Integer>();
        while (!stack.isEmpty()) {
            int p = stack.pop();
            if (!visited.add(p)) continue;
            var kids = childrenMap.get(p);
            if (kids != null) {
                for (int kid : kids) {
                    var info = processInfo.get(kid);
                    if (info != null && info.cpuPct() > cpuThreshold) return true;
                    stack.push(kid);
                }
            }
        }
        return false;
    }

    /** Get listening TCP ports via: lsof -i -P -n -sTCP:LISTEN */
    public static Map<Integer, List<Integer>> getListeningPorts() {
        var map = new HashMap<Integer, List<Integer>>();
        try {
            var pb = new ProcessBuilder("lsof", "-i", "-P", "-n", "-sTCP:LISTEN");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                reader.readLine(); // skip header
                String line;
                while ((line = reader.readLine()) != null) {
                    var parts = line.trim().split("\\s+");
                    boolean isTcpListen = parts.length >= 9
                            && "TCP".equals(parts[7])
                            && line.contains("(LISTEN)");
                    if (isTcpListen) {
                        try {
                            int pid = Integer.parseInt(parts[1]);
                            String addr = parts[8];
                            int colonIdx = addr.lastIndexOf(':');
                            if (colonIdx >= 0) {
                                int port = Integer.parseInt(addr.substring(colonIdx + 1));
                                map.computeIfAbsent(pid, k -> new ArrayList<>()).add(port);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (Exception ignored) {}
        return map;
    }

    /**
     * Check if a command string has a given binary name in executable position.
     * Checks the first two argv tokens (covers direct invocation and interpreter-wrapped scripts).
     */
    public static boolean cmdHasBinary(String cmd, String name) {
        if (cmd.isEmpty() || name.isEmpty()) return false;
        var tokens = cmd.split("\\s+");
        int limit = Math.min(tokens.length, 2);
        for (int i = 0; i < limit; i++) {
            String base = tokens[i];
            int slash = base.lastIndexOf('/');
            if (slash >= 0) base = base.substring(slash + 1);
            if (base.equals(name)) return true;
        }
        return false;
    }

    /** Get git status (added, modified) counts for a working directory. */
    public static int[] collectGitStats(String cwd) {
        if (!Files.isDirectory(Path.of(cwd))) return new int[]{0, 0};
        int added = 0, modified = 0;
        try {
            var pb = new ProcessBuilder("git", "-C", cwd, "status", "--porcelain");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() < 2) continue;
                    String status = line.substring(0, 2);
                    if (status.contains("?") || status.contains("A")) {
                        added++;
                    } else if (status.contains("M")) {
                        modified++;
                    }
                }
            }
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (Exception ignored) {}
        return new int[]{added, modified};
    }

    /**
     * Collect all descendant processes recursively (not just direct children).
     * Returns list of child PIDs including grandchildren.
     */
    public static List<Integer> allDescendants(int pid, Map<Integer, List<Integer>> childrenMap) {
        var result = new ArrayList<Integer>();
        var stack = new ArrayDeque<>(childrenMap.getOrDefault(pid, List.of()));
        var visited = new HashSet<Integer>();
        while (!stack.isEmpty()) {
            int cpid = stack.pop();
            if (!visited.add(cpid)) continue;
            result.add(cpid);
            var grandchildren = childrenMap.get(cpid);
            if (grandchildren != null) stack.addAll(grandchildren);
        }
        return result;
    }

    /** Batch-get cwd for multiple PIDs via single lsof call. */
    public static Map<Integer, String> getCwdBatch(int[] pids) {
        if (pids.length == 0) return Map.of();
        var result = new HashMap<Integer, String>();
        var pidStr = new StringBuilder();
        for (int i = 0; i < pids.length; i++) {
            if (i > 0) pidStr.append(',');
            pidStr.append(pids[i]);
        }
        try {
            var pb = new ProcessBuilder("lsof", "-a", "-p", pidStr.toString(), "-d", "cwd", "-Fn");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var output = new String(proc.getInputStream().readAllBytes()).trim();
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
            // Output format: p<pid>\nf<fd>\nn<path>\np<pid>\nf<fd>\nn<path>...
            int currentPid = -1;
            for (String line : output.lines().toList()) {
                if (line.startsWith("p")) {
                    try { currentPid = Integer.parseInt(line.substring(1)); }
                    catch (NumberFormatException e) { currentPid = -1; }
                } else if (line.startsWith("n") && currentPid > 0) {
                    result.put(currentPid, line.substring(1));
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Get the command string for a PID via ps. Returns null if not found. */
    public static String getCommandForPid(int pid) {
        try {
            var pb = new ProcessBuilder("ps", "-ww", "-o", "command=", "-p", String.valueOf(pid));
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var output = new String(proc.getInputStream().readAllBytes()).trim();
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
            return output.isEmpty() ? null : output;
        } catch (Exception e) {
            return null;
        }
    }

    /** Check if target PID is a descendant of ancestor PID, using provided process map. */
    public static boolean isDescendantOf(int target, int ancestor, Map<Integer, ProcInfo> procs) {
        if (target == ancestor) return true;
        int current = target;
        for (int depth = 0; depth < 50; depth++) {
            var info = procs.get(current);
            if (info == null) return false;
            int parent = info.ppid();
            if (parent == ancestor) return true;
            if (parent == 0 || parent == 1 || parent == current) return false;
            current = parent;
        }
        return false;
    }

    /** Check if target PID is a descendant of ancestor PID. */
    public static boolean isDescendantOf(int target, int ancestor) {
        if (target == ancestor) return true;
        var procs = getProcessInfo();
        int current = target;
        for (int depth = 0; depth < 50; depth++) {
            var info = procs.get(current);
            if (info == null) return false;
            int parent = info.ppid();
            if (parent == ancestor) return true;
            if (parent == 0 || parent == 1 || parent == current) return false;
            current = parent;
        }
        return false;
    }
}
