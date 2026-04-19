package dev.abtop.ui;

import dev.abtop.App;
import dev.abtop.Theme;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;

import static dev.abtop.ui.UiUtil.*;

public final class TokensPanel {
    private TokensPanel() {}

    public static void draw(Frame frame, App app, Rect area, Theme theme) {
        var block = AbtopRenderer.btopBlock("tokens", "³", theme.netBox(), theme);
        frame.renderWidget(block, area);

        var inner = AbtopRenderer.innerRect(area);
        var lines = new ArrayList<Line>();

        long totalIn = 0, totalOut = 0, totalCache = 0, totalCreate = 0;
        int totalTurns = 0;
        for (var s : app.getSessions()) {
            totalIn += s.getTotalInputTokens();
            totalOut += s.getTotalOutputTokens();
            totalCache += s.getTotalCacheRead();
            totalCreate += s.getTotalCacheCreate();
            totalTurns += s.getTurnCount();
        }
        long total = totalIn + totalOut + totalCache + totalCreate;

        lines.add(Line.from(
                Span.styled(" Total  ", Style.create().fg(theme.graphText())),
                Span.styled(fmtTokens(total), Style.create().fg(theme.mainFg()).bold())));
        lines.add(Line.from(
                Span.styled(" Input  ", Style.create().fg(theme.graphText())),
                Span.styled(fmtTokens(totalIn), Style.create().fg(theme.mainFg()))));
        lines.add(Line.from(
                Span.styled(" Output ", Style.create().fg(theme.graphText())),
                Span.styled(fmtTokens(totalOut), Style.create().fg(theme.mainFg()))));
        lines.add(Line.from(
                Span.styled(" Cache  ", Style.create().fg(theme.graphText())),
                Span.styled(fmtTokens(totalCache + totalCreate), Style.create().fg(theme.mainFg()))));

        // Sparkline for selected session
        var sessions = app.getSessions();
        if (!sessions.isEmpty() && app.getSelected() < sessions.size()) {
            var selected = sessions.get(app.getSelected());
            var history = selected.getTokenHistory();
            if (!history.isEmpty()) {
                long max = history.stream().mapToLong(Long::longValue).max().orElse(1);
                double[] normalized = history.stream()
                        .mapToDouble(v -> max > 0 ? (double) v / max : 0)
                        .toArray();
                int sparkW = Math.max(4, inner.width() - 2);
                var cpuGrad = makeGradient(theme.cpuGrad());
                var sparkSpans = new ArrayList<Span>();
                sparkSpans.add(Span.styled(" ", Style.create()));
                sparkSpans.addAll(brailleSparkline(normalized, sparkW, cpuGrad, theme.graphText()));
                lines.add(Line.from(sparkSpans.toArray(Span[]::new)));
            }
        }

        lines.add(Line.from(
                Span.styled(" Turns: ", Style.create().fg(theme.graphText())),
                Span.styled(String.valueOf(totalTurns), Style.create().fg(theme.mainFg()))));

        if (totalTurns > 0) {
            lines.add(Line.from(
                    Span.styled(" Avg: ", Style.create().fg(theme.graphText())),
                    Span.styled(fmtTokens(total / totalTurns) + "/t", Style.create().fg(theme.mainFg()))));
        }

        frame.renderWidget(Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines.toArray(Line[]::new)))
                .build(), inner);
    }
}
