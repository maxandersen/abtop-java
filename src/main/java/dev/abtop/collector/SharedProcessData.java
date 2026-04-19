package dev.abtop.collector;

import java.util.List;
import java.util.Map;

/**
 * Process data fetched once per tick and shared across all collectors.
 * Avoids duplicate ps/lsof calls.
 */
public record SharedProcessData(
        Map<Integer, ProcessUtil.ProcInfo> processInfo,
        Map<Integer, List<Integer>> childrenMap,
        Map<Integer, List<Integer>> ports) {

    /** Fetch process info; reuse cached ports when provided. */
    public static SharedProcessData fetch(Map<Integer, List<Integer>> cachedPorts) {
        var processInfo = ProcessUtil.getProcessInfo();
        var childrenMap = ProcessUtil.getChildrenMap(processInfo);
        var ports = cachedPorts != null ? cachedPorts : ProcessUtil.getListeningPorts();
        return new SharedProcessData(processInfo, childrenMap, ports);
    }
}
