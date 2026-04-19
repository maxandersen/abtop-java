package dev.abtop;

import dev.abtop.collector.Json;
import dev.abtop.ui.AbtopRenderer;
import dev.abtop.ui.UiUtil;
import dev.abtop.model.AgentSession;
import dev.abtop.model.SessionStatus;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.*;

@Command(name = "abtop", mixinStandardHelpOptions = true,
        version = "abtop 1.0.0",
        description = "AI agent monitor for your terminal")
public class AbtopCommand implements Runnable {

    @Option(names = "--setup", description = "Install StatusLine hook for rate limit collection")
    boolean setup;

    @Option(names = "--once", description = "Print snapshot and exit")
    boolean once;

    @Option(names = "--json", description = "Output sessions as JSON (implies --once)")
    boolean json;

    @Option(names = "--jump", description = "Jump to tmux/zellij pane for given PID")
    int jumpPid = -1;

    @Option(names = "--theme", description = "Theme name")
    String themeName;

    @Override
    public void run() {
        if (setup) {
            Setup.run();
            return;
        }

        if (json) once = true;

        Theme theme = resolveTheme();
        var app = new App(theme);

        if (jumpPid > 0) {
            app.tick();
            // Select the session matching the PID
            var sessions = app.getSessions();
            for (int i = 0; i < sessions.size(); i++) {
                if (sessions.get(i).getPid() == jumpPid) {
                    for (int j = 0; j < i; j++) app.selectNext();
                    break;
                }
            }
            String result = app.jumpToSession();
            if (result != null) {
                System.err.println(result);
                System.exit(1);
            }
            app.shutdown();
            return;
        }

        if (once) {
            app.tick();
            // Wait for summaries (up to 30s)
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                app.drainAndRetrySummaries();
                if (!app.hasPendingSummaries() && !app.hasRetryableSummaries()) break;
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
            if (json) {
                printJson(app);
            } else {
                printSnapshot(app);
            }
            app.shutdown();
            return;
        }

        // TUI mode
        var config = TuiConfig.builder()
                .tickRate(Duration.ofMillis(500))
                .build();

        try (var tui = TuiRunner.create(config)) {
            app.tick();
            tui.run(
                    (event, runner) -> switch (event) {
                        case KeyEvent k when isChar(k, 'q') -> { runner.quit(); yield false; }
                        case KeyEvent k when isChar(k, 'r') -> { app.tick(); yield true; }
                        case KeyEvent k when isDown(k) -> { app.selectNext(); yield true; }
                        case KeyEvent k when isUp(k) -> { app.selectPrev(); yield true; }
                        case KeyEvent k when isChar(k, 'x') -> { app.killSelected(); yield true; }
                        case KeyEvent k when isChar(k, 'X') -> { app.killOrphanPorts(); yield true; }
                        case KeyEvent k when isChar(k, 't') -> { app.cycleTheme(); yield true; }
                        case KeyEvent k when isEnter(k) || isChar(k, 'g') -> {
                            var msg = app.jumpToSession();
                            if (msg != null) app.setStatus(msg);
                            yield true;
                        }
                        case TickEvent t -> {
                            if (app.shouldTick()) app.tick();
                            yield true;
                        }
                        default -> false;
                    },
                    frame -> AbtopRenderer.draw(frame, app)
            );
        } catch (Exception e) {
            System.err.println("TUI error: " + e.getMessage());
        } finally {
            app.shutdown();
        }
    }

    private Theme resolveTheme() {
        String name = themeName;
        if (name == null || name.isEmpty()) {
            name = Config.loadTheme();
        }
        var t = Theme.byName(name);
        return t != null ? t : Theme.defaultTheme();
    }

    private void printJson(App app) {
        var sessions = app.getSessions();
        var list = new ArrayList<Map<String, Object>>();
        for (var s : sessions) {
            var map = new LinkedHashMap<String, Object>();
            map.put("pid", s.getPid());
            map.put("agent", s.getAgentCli());
            map.put("sessionId", s.getSessionId());
            map.put("project", s.getProjectName());
            map.put("cwd", s.getCwd());
            map.put("status", s.getStatus().name());
            map.put("model", s.getModel());
            map.put("contextPercent", Math.round(s.getContextPercent()));
            map.put("totalTokens", s.totalTokens());
            map.put("turnCount", s.getTurnCount());
            map.put("memMb", s.getMemMb());
            map.put("elapsed", s.elapsedDisplay());
            map.put("summary", sanitize(app.sessionSummary(s)));
            map.put("gitBranch", s.getGitBranch());
            var tasks = s.getCurrentTasks();
            map.put("currentTask", tasks.isEmpty() ? "" : sanitize(tasks.getLast()));
            list.add(map);
        }
        try {
            System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(list));
        } catch (Exception e) {
            System.err.println("JSON error: " + e.getMessage());
        }
    }

    private void printSnapshot(App app) {
        System.out.printf("abtop — %d sessions%n%n", app.getSessions().size());
        for (var session : app.getSessions()) {
            String status = switch (session.getStatus()) {
                case WORKING -> "● Work";
                case WAITING -> "◌ Wait";
                case DONE -> "✓ Done";
            };
            String sid = session.getSessionId().length() >= 7
                    ? session.getSessionId().substring(0, 7) : session.getSessionId();
            String label = session.getProjectName() + "(" + sid + ")";
            String summary = sanitize(app.sessionSummary(session));
            System.out.printf("  %d %-20s %s %s %-10s CTX:%3.0f%% Tok:%s Mem:%dM %s%n",
                    session.getPid(),
                    sanitize(label),
                    summary,
                    status,
                    session.getModel().replace("claude-", ""),
                    session.getContextPercent(),
                    UiUtil.fmtTokens(session.totalTokens()),
                    session.getMemMb(),
                    session.elapsedDisplay());
            if (!session.getCurrentTasks().isEmpty()) {
                System.out.println("       └─ " + sanitize(session.getCurrentTasks().getLast()));
            }
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.codePoints()
                .filter(cp -> !Character.isISOControl(cp)
                        && !(cp >= 0x202A && cp <= 0x202E)
                        && !(cp >= 0x2066 && cp <= 0x2069)
                        && cp != 0x200E && cp != 0x200F)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    // Key event helpers — adapt to TamboUI's KeyEvent API
    private static boolean isChar(KeyEvent k, char c) {
        return k.character() == c;
    }

    private static boolean isDown(KeyEvent k) {
        return k.character() == 'j'
                || k.isDown();
    }

    private static boolean isUp(KeyEvent k) {
        return k.character() == 'k'
                || k.isUp();
    }

    private static boolean isEnter(KeyEvent k) {
        return k.isKey(dev.tamboui.tui.event.KeyCode.ENTER)
                || k.isConfirm()
                || k.character() == '\n'
                || k.character() == '\r';
    }
}
