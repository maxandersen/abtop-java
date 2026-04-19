package dev.abtop;

import dev.abtop.model.*;
import dev.abtop.ui.AbtopRenderer;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

import static dev.tamboui.export.ExportRequest.export;

/**
 * Generates SVG screenshots of abtop with mock data.
 * Runs without a terminal — useful for docs, landing pages, CI.
 *
 * Usage: java -cp abtop.jar dev.abtop.ScreenshotGenerator [output.svg] [width] [height]
 */
public final class ScreenshotGenerator {

    private static final int DEFAULT_WIDTH = 140;
    private static final int DEFAULT_HEIGHT = 38;

    public static void main(String[] args) throws Exception {
        Path output = args.length > 0 ? Path.of(args[0]) : Path.of("docs/assets/screenshot.svg");
        int width = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_WIDTH;
        int height = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_HEIGHT;

        var app = buildMockApp();

        Rect area = new Rect(0, 0, width, height);
        Buffer buffer = Buffer.empty(area);
        Frame frame = Frame.forTesting(buffer);

        AbtopRenderer.draw(frame, app);

        java.nio.file.Files.createDirectories(output.getParent());
        export(buffer).svg()
                .options(o -> o.title("jabtop").chrome(true))
                .toFile(output);

        System.out.println("Screenshot saved to " + output.toAbsolutePath());
    }

    private static App buildMockApp() {
        var theme = Theme.byName("btop");
        var app = new App(theme);

        // Build mock sessions
        var sessions = new ArrayList<AgentSession>();

        // Claude Code session — working, high context
        var s1 = new AgentSession("claude", 7336);
        s1.setSessionId("2f029acc-abcd-1234-5678-9abcdef01234");
        s1.setCwd("/Users/you/code/abtop");
        s1.setProjectName("abtop");
        s1.setStartedAt(System.currentTimeMillis() - 47 * 60_000);
        s1.setStatus(SessionStatus.WORKING);
        s1.setModel("claude-opus-4-6");
        s1.setEffort("high");
        s1.setContextPercent(82.0);
        s1.setTotalInputTokens(402_000);
        s1.setTotalOutputTokens(89_000);
        s1.setTotalCacheRead(710_000);
        s1.setTotalCacheCreate(12_000);
        s1.setTurnCount(48);
        s1.setCurrentTasks(List.of("Edit src/pay.rs"));
        s1.setMemMb(384);
        s1.setVersion("2.1.86");
        s1.setGitBranch("main");
        s1.setGitAdded(3);
        s1.setGitModified(18);
        s1.setMemFileCount(4);
        s1.setMemLineCount(12);
        s1.setChildren(List.of(
                new ChildProcess(7401, "cargo build --release", 128_000, null),
                new ChildProcess(7455, "rust-analyzer", 256_000, null)
        ));
        s1.setSubagents(List.of(
                new SubAgent("explore-data", "done", 12_000),
                new SubAgent("run-tests", "working", 8_000)
        ));
        s1.setTokenHistory(generateTokenHistory(200));
        s1.setInitialPrompt("Stripe payment integration");
        sessions.add(s1);

        // Codex session — waiting
        var s2 = new AgentSession("codex", 8840);
        s2.setSessionId("019bcadb-d58d-74f3-8115-08e5e71c3c24");
        s2.setCwd("/Users/you/code/prediction");
        s2.setProjectName("prediction");
        s2.setStartedAt(System.currentTimeMillis() - 22 * 60_000);
        s2.setStatus(SessionStatus.WAITING);
        s2.setModel("o3");
        s2.setContextPercent(91.0);
        s2.setTotalInputTokens(240_000);
        s2.setTotalOutputTokens(45_000);
        s2.setTotalCacheRead(55_000);
        s2.setTurnCount(12);
        s2.setCurrentTasks(List.of("waiting for input"));
        s2.setMemMb(210);
        s2.setVersion("0.78.0");
        s2.setGitBranch("feat/x");
        s2.setGitAdded(1);
        s2.setGitModified(2);
        s2.setInitialPrompt("ML model prediction pipeline");
        sessions.add(s2);

        // pi session — working
        var s3 = new AgentSession("pi", 9012);
        s3.setSessionId("pi-sess-a1b2c3d4");
        s3.setCwd("/Users/you/code/api-server");
        s3.setProjectName("api-server");
        s3.setStartedAt(System.currentTimeMillis() - 15 * 60_000);
        s3.setStatus(SessionStatus.WORKING);
        s3.setModel("claude-sonnet-4-6");
        s3.setContextPercent(45.0);
        s3.setTotalInputTokens(120_000);
        s3.setTotalOutputTokens(35_000);
        s3.setTotalCacheRead(25_000);
        s3.setTurnCount(8);
        s3.setCurrentTasks(List.of("bash cargo build"));
        s3.setMemMb(156);
        s3.setGitBranch("main");
        s3.setGitModified(5);
        s3.setChildren(List.of(
                new ChildProcess(9100, "node server.js", 92_000, 3000)
        ));
        s3.setInitialPrompt("REST API endpoint refactor");
        sessions.add(s3);

        // opencode session — waiting
        var s4 = new AgentSession("opencode", 4521);
        s4.setSessionId("ses_25cd92e53ffe");
        s4.setCwd("/Users/you/code/jbang");
        s4.setProjectName("jbang");
        s4.setStartedAt(System.currentTimeMillis() - 5 * 60_000);
        s4.setStatus(SessionStatus.WAITING);
        s4.setModel("big-pickle");
        s4.setContextPercent(22.0);
        s4.setTotalInputTokens(10_000);
        s4.setTotalOutputTokens(3_000);
        s4.setTotalCacheRead(2_000);
        s4.setTurnCount(2);
        s4.setCurrentTasks(List.of("waiting for input"));
        s4.setMemMb(64);
        s4.setInitialPrompt("Repository contents overview");
        sessions.add(s4);

        // Inject mock data via reflection (App has private fields)
        try {
            var sessionsField = App.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            sessionsField.set(app, sessions);

            var tokenRatesField = App.class.getDeclaredField("tokenRates");
            tokenRatesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var rates = (ArrayDeque<Double>) tokenRatesField.get(app);
            for (double v : generateRateHistory(200)) rates.addLast(v);

            var rateLimitsField = App.class.getDeclaredField("rateLimits");
            rateLimitsField.setAccessible(true);
            rateLimitsField.set(app, List.of(
                    new RateLimitInfo("claude", 35.0, System.currentTimeMillis() / 1000 + 7200,
                            12.0, System.currentTimeMillis() / 1000 + 604800, System.currentTimeMillis() / 1000),
                    new RateLimitInfo("codex", 9.0, System.currentTimeMillis() / 1000 + 14400,
                            14.0, System.currentTimeMillis() / 1000 + 504000, System.currentTimeMillis() / 1000)
            ));

            var orphanField = App.class.getDeclaredField("orphanPorts");
            orphanField.setAccessible(true);
            orphanField.set(app, List.of(
                    new OrphanPort(4000, 6789, "node old-server.js", "old-project")
            ));

            // Put summaries
            var summariesField = App.class.getDeclaredField("summaries");
            summariesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var summaries = (java.util.Map<String, String>) summariesField.get(app);
            summaries.put(s1.getSessionId(), "Stripe payment integration");
            summaries.put(s2.getSessionId(), "ML prediction pipeline");
            summaries.put(s3.getSessionId(), "REST API refactor");
            summaries.put(s4.getSessionId(), "Repository overview");
        } catch (Exception e) {
            System.err.println("Warning: couldn't set mock data: " + e.getMessage());
        }

        return app;
    }

    private static List<Long> generateTokenHistory(int points) {
        var history = new ArrayList<Long>();
        var rand = new java.util.Random(42);
        for (int i = 0; i < points; i++) {
            double t = (double) i / points;
            long base = (long) (5000 + 20000 * Math.sin(t * Math.PI * 3));
            history.add(Math.max(0, base + rand.nextInt(5000)));
        }
        return history;
    }

    private static double[] generateRateHistory(int points) {
        var rates = new double[points];
        var rand = new java.util.Random(7);
        for (int i = 0; i < points; i++) {
            double t = (double) i / points;
            rates[i] = Math.max(0, 3000 + 8000 * Math.sin(t * Math.PI * 4) + rand.nextInt(2000));
        }
        return rates;
    }
}
