package dev.abtop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public final class Config {

    private Config() {}

    public static String loadTheme() {
        var path = configPath();
        if (path == null || !Files.exists(path)) return "btop";
        try {
            for (String line : Files.readAllLines(path)) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                var parts = line.split("=", 2);
                if (parts.length == 2 && parts[0].trim().equals("theme")) {
                    String val = parts[1].trim();
                    int comment = val.indexOf('#');
                    if (comment >= 0) val = val.substring(0, comment).trim();
                    val = val.replace("\"", "").replace("'", "");
                    return val;
                }
            }
        } catch (IOException ignored) {}
        return "btop";
    }

    public static void saveTheme(String name) throws IOException {
        var path = configPath();
        if (path == null) throw new IOException("no config directory");
        Files.createDirectories(path.getParent());

        var lines = new ArrayList<String>();
        boolean found = false;
        if (Files.exists(path)) {
            for (String line : Files.readAllLines(path)) {
                if (line.split("=", 2)[0].trim().equals("theme")) {
                    lines.add("theme = \"" + name + "\"");
                    found = true;
                } else {
                    lines.add(line);
                }
            }
        }
        if (!found) lines.add("theme = \"" + name + "\"");
        Files.writeString(path, String.join("\n", lines) + "\n");
    }

    private static Path configPath() {
        String home = System.getProperty("user.home");
        if (home == null) return null;
        String os = System.getProperty("os.name", "").toLowerCase();
        Path configDir;
        if (os.contains("mac")) {
            configDir = Path.of(home, "Library", "Application Support");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            configDir = xdg != null ? Path.of(xdg) : Path.of(home, ".config");
        }
        return configDir.resolve("abtop").resolve("config.toml");
    }
}
