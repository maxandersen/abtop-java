package dev.abtop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Installs the StatusLine hook for Claude Code rate limit collection.
 * Invoked via: abtop --setup
 */
public final class Setup {

    private static final String STATUSLINE_SCRIPT = """
            #!/bin/bash
            # abtop StatusLine hook — writes rate limit data for abtop to read.
            # Installed by: abtop --setup
            INPUT=""
            while IFS= read -r -t 5 line || [ -n "$line" ]; do
                INPUT="${INPUT}${line}
            "
            done
            [ -z "$INPUT" ] && exit 0
            printf '%s' "$INPUT" | python3 -c "
            import sys, json, time, os
            data = json.load(sys.stdin)
            rl = data.get('rate_limits')
            if not rl:
                sys.exit(0)
            out = {'source': 'claude', 'updated_at': int(time.time())}
            fh = rl.get('five_hour')
            if fh:
                out['five_hour'] = {'used_percentage': fh.get('used_percentage', 0), 'resets_at': fh.get('resets_at', 0)}
            sd = rl.get('seven_day')
            if sd:
                out['seven_day'] = {'used_percentage': sd.get('used_percentage', 0), 'resets_at': sd.get('resets_at', 0)}
            config_dir = os.environ.get('CLAUDE_CONFIG_DIR', os.path.join(os.path.expanduser('~'), '.claude'))
            with open(os.path.join(config_dir, 'abtop-rate-limits.json'), 'w') as f:
                json.dump(out, f)
            " 2>/dev/null
            """;

    private static final ObjectMapper MAPPER = dev.abtop.collector.Json.MAPPER;

    private Setup() {}

    public static void run() {
        System.out.println("abtop --setup: configuring Claude Code StatusLine hook\n");

        Path dir = claudeDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("  ✗ failed to create " + dir + ": " + e.getMessage());
            System.exit(1);
        }

        // Step 1: Write the statusline script
        Path script = dir.resolve("abtop-statusline.sh");
        try {
            Files.writeString(script, STATUSLINE_SCRIPT);
            try {
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {}
            System.out.println("  ✓ wrote " + script);
        } catch (IOException e) {
            System.err.println("  ✗ failed to write " + script + ": " + e.getMessage());
            System.exit(1);
        }

        // Step 2: Update settings.json
        Path settingsFile = dir.resolve("settings.json");
        try {
            ObjectNode settings;
            if (Files.exists(settingsFile)) {
                settings = (ObjectNode) MAPPER.readTree(settingsFile.toFile());
            } else {
                settings = MAPPER.createObjectNode();
            }

            String expectedCmd = script.toString();
            var existing = settings.get("statusLine");
            if (existing != null && existing.isObject()) {
                var cmd = existing.get("command");
                if (cmd != null && !cmd.asText().isEmpty() && !cmd.asText().equals(expectedCmd)) {
                    System.err.println("  ⚠ statusLine already configured: " + cmd.asText());
                    System.err.println("    to override, remove the existing statusLine key from:");
                    System.err.println("    " + settingsFile);
                    System.exit(1);
                }
            }

            var statusLine = MAPPER.createObjectNode();
            statusLine.put("type", "command");
            statusLine.put("command", expectedCmd);
            settings.set("statusLine", statusLine);

            Files.writeString(settingsFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
            System.out.println("  ✓ updated " + settingsFile);
        } catch (IOException e) {
            System.err.println("  ✗ failed to update " + settingsFile + ": " + e.getMessage());
            System.exit(1);
        }

        System.out.println("\n  done! rate limit data will appear in abtop after the next Claude response.");
        System.out.println("  restart any running Claude Code sessions to activate.");
    }

    private static Path claudeDir() {
        String envDir = System.getenv("CLAUDE_CONFIG_DIR");
        if (envDir != null) {
            Path p = Path.of(envDir);
            if (Files.isDirectory(p)) return p;
        }
        return Path.of(System.getProperty("user.home", ""), ".claude");
    }
}
