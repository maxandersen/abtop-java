package dev.abtop.ui;

import dev.abtop.App;
import dev.abtop.Theme;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.paragraph.Paragraph;

public final class HeaderPanel {
    private HeaderPanel() {}

    public static void draw(Frame frame, App app, Rect area, Theme theme) {
        int sessionCount = app.getSessions().size();
        String mux = detectMultiplexer();

        var spans = new java.util.ArrayList<Span>();
        spans.add(Span.styled(" abtop", Style.create().fg(theme.hiFg()).bold()));
        spans.add(Span.styled(" — " + sessionCount + " session" + (sessionCount != 1 ? "s" : ""),
                Style.create().fg(theme.mainFg())));
        if (!mux.isEmpty()) {
            spans.add(Span.styled("  [" + mux + "]", Style.create().fg(theme.inactiveFg())));
        } else {
            spans.add(Span.styled("  [no mux]", Style.create().fg(theme.inactiveFg())));
        }

        var line = Line.from(spans.toArray(Span[]::new));
        frame.renderWidget(Paragraph.builder().text(dev.tamboui.text.Text.from(line)).build(), area);
    }

    private static String detectMultiplexer() {
        var zellijSession = System.getenv("ZELLIJ_SESSION_NAME");
        if (zellijSession != null && !zellijSession.isEmpty()) {
            return "zellij:" + zellijSession;
        }
        var tmux = System.getenv("TMUX");
        if (tmux != null && !tmux.isEmpty()) {
            // TMUX=/tmp/tmux-501/default,1234,0 — extract session name
            try {
                var pb = new ProcessBuilder("tmux", "display-message", "-p", "#S");
                var proc = pb.start();
                var name = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                return name.isEmpty() ? "tmux" : "tmux:" + name;
            } catch (Exception e) {
                return "tmux";
            }
        }
        return "";
    }
}
