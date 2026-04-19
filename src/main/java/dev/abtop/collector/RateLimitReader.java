package dev.abtop.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.abtop.model.RateLimitInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads rate limit info from StatusLine hook output and Codex cache files.
 */
public final class RateLimitReader {

    private static final String CLAUDE_RATE_FILE = "abtop-rate-limits.json";
    private static final String CODEX_CACHE_FILE = "codex-rate-limits.json";
    private static final ObjectMapper MAPPER = Json.MAPPER;

    private RateLimitReader() {}

    /** Read rate limit info from all known sources. */
    public static List<RateLimitInfo> readRateLimits() {
        var results = new ArrayList<RateLimitInfo>();
        Path claudeDir = claudeConfigDir();
        if (claudeDir != null) {
            readRateFile(claudeDir.resolve(CLAUDE_RATE_FILE), "claude", true)
                    .ifPresent(results::add);
        }
        return results;
    }

    /** Read cached Codex rate limit (fallback when no live session provides one). */
    public static Optional<RateLimitInfo> readCodexCache() {
        return codexCachePath().flatMap(p -> readRateFile(p, "codex", false));
    }

    /** Write Codex rate limit to cache file (atomic: write temp + rename). */
    public static void writeCodexCache(RateLimitInfo info) {
        codexCachePath().ifPresent(path -> {
            try {
                Files.createDirectories(path.getParent());
                String json = String.format(
                        "{\"source\":\"codex\",\"five_hour\":%s,\"seven_day\":%s,\"updated_at\":%s}",
                        windowJson(info.fiveHourPct(), info.fiveHourResetsAt()),
                        windowJson(info.sevenDayPct(), info.sevenDayResetsAt()),
                        info.updatedAt() != null ? info.updatedAt().toString() : "null"
                );
                var tmp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.writeString(tmp, json);
                Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {}
        });
    }

    private static String windowJson(Double pct, Long resetsAt) {
        if (pct == null) return "null";
        long r = resetsAt != null ? resetsAt : 0;
        return String.format("{\"used_percentage\":%s,\"resets_at\":%d}", pct, r);
    }

    private static Optional<RateLimitInfo> readRateFile(Path path, String defaultSource, boolean checkStaleness) {
        try {
            if (!Files.exists(path)) return Optional.empty();
            var root = MAPPER.readTree(path.toFile());

            if (checkStaleness) {
                var updatedNode = root.get("updated_at");
                if (updatedNode != null && updatedNode.isNumber()) {
                 long updated = updatedNode.asLong();
                    if (Instant.now().getEpochSecond() - updated > 600) {
                        return Optional.empty();
                    }
                }
            }

            var fiveHour = root.get("five_hour");
            var sevenDay = root.get("seven_day");
            if ((fiveHour == null || fiveHour.isNull()) && (sevenDay == null || sevenDay.isNull())) {
                return Optional.empty();
            }

            String source = root.has("source") && !root.get("source").asText().isEmpty()
                    ? root.get("source").asText() : defaultSource;

            return Optional.of(new RateLimitInfo(
                    source,
                    extractPct(fiveHour),
                    extractResets(fiveHour),
                    extractPct(sevenDay),
                    extractResets(sevenDay),
                    root.has("updated_at") ? root.get("updated_at").asLong() : null
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Double extractPct(JsonNode window) {
        if (window == null || window.isNull()) return null;
        var node = window.get("used_percentage");
        return node != null ? node.asDouble() : null;
    }

    private static Long extractResets(JsonNode window) {
        if (window == null || window.isNull()) return null;
        var node = window.get("resets_at");
        return node != null ? node.asLong() : null;
    }

    static Path claudeConfigDir() {
        String envDir = System.getenv("CLAUDE_CONFIG_DIR");
        if (envDir != null && Files.isDirectory(Path.of(envDir))) {
            return Path.of(envDir);
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            return Path.of(home, ".claude");
        }
        return null;
    }

    private static Optional<Path> codexCachePath() {
        String home = System.getProperty("user.home");
        if (home == null) return Optional.empty();
        // macOS: ~/Library/Caches, Linux: ~/.cache
        String os = System.getProperty("os.name", "").toLowerCase();
        Path cacheDir;
        if (os.contains("mac")) {
            cacheDir = Path.of(home, "Library", "Caches");
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            cacheDir = xdg != null ? Path.of(xdg) : Path.of(home, ".cache");
        }
        return Optional.of(cacheDir.resolve("abtop").resolve(CODEX_CACHE_FILE));
    }
}
