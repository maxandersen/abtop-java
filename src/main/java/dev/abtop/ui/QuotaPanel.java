package dev.abtop.ui;

import dev.abtop.App;
import dev.abtop.Theme;
import dev.abtop.model.RateLimitInfo;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static dev.abtop.ui.UiUtil.*;

public final class QuotaPanel {
    private QuotaPanel() {}

    public static void draw(Frame frame, App app, Rect area, Theme theme) {
        var block = AbtopRenderer.btopBlock("quota", "²", theme.memBox(), theme);
        frame.renderWidget(block, area);

        var inner = AbtopRenderer.innerRect(area);
        var lines = new ArrayList<Line>();
        var usedGrad = makeGradient(theme.usedGrad());

        for (var rl : app.getRateLimits()) {
            lines.add(Line.from(Span.styled(
                    " " + rl.source().toUpperCase(),
                    Style.create().fg(theme.title()).bold())));
            addWindow(lines, "5h", rl.fiveHourPct(), rl.fiveHourResetsAt(),
                    inner.width() - 2, usedGrad, theme);
            addWindow(lines, "7d", rl.sevenDayPct(), rl.sevenDayResetsAt(),
                    inner.width() - 2, usedGrad, theme);
        }

        if (app.getRateLimits().isEmpty()) {
            lines.add(Line.from(Span.styled(" —", Style.create().fg(theme.inactiveFg()))));
        }

        frame.renderWidget(Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines.toArray(Line[]::new)))
                .build(), inner);
    }

    private static void addWindow(List<Line> lines, String label, Double pct, Long resetsAt,
                                   int width, Color[] gradient, Theme theme) {
        if (pct == null) {
            lines.add(Line.from(Span.styled(" " + label + " —", Style.create().fg(theme.inactiveFg()))));
            return;
        }
        double remaining = 100.0 - pct;
        int barWidth = Math.max(4, width - 12);
        var spans = new ArrayList<Span>();
        spans.add(Span.styled(" " + label + " ", Style.create().fg(theme.graphText())));
        spans.addAll(remainingBar(remaining, barWidth, gradient, theme.meterBg()));
        spans.add(Span.styled(String.format(" %3.0f%%", pct),
                Style.create().fg(gradAt(gradient, pct))));
        lines.add(Line.from(spans.toArray(Span[]::new)));

        if (resetsAt != null && resetsAt > 0) {
            long secsLeft = resetsAt - Instant.now().getEpochSecond();
            String resetStr = secsLeft > 0
                    ? "   resets " + formatDuration(secsLeft)
                    : "   reset now";
            lines.add(Line.from(Span.styled(resetStr, Style.create().fg(theme.inactiveFg()))));
        }
    }

    private static String formatDuration(long secs) {
        if (secs >= 3600) return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
        if (secs >= 60) return (secs / 60) + "m";
        return secs + "s";
    }
}
