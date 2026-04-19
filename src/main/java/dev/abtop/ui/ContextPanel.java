package dev.abtop.ui;

import dev.abtop.App;
import dev.abtop.Theme;
import dev.abtop.model.SessionStatus;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Direction;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;

import static dev.abtop.ui.UiUtil.*;

public final class ContextPanel {
    private ContextPanel() {}

    public static void draw(Frame frame, App app, Rect area, Theme theme) {
        var cpuGrad = makeGradient(theme.cpuGrad());
        var block = AbtopRenderer.btopBlock("context", "¹", theme.cpuBox(), theme);
        frame.renderWidget(block, area);

        var inner = AbtopRenderer.innerRect(area);

        if (inner.height() <= 1) {
            drawCompact(frame, app, inner, cpuGrad, theme);
            return;
        }

        var halves = Layout.horizontal()
                .constraints(Constraint.percentage(65), Constraint.percentage(35))
                .split(inner).toArray(Rect[]::new);

        drawSparkline(frame, app, halves[0], cpuGrad, theme);
        drawBars(frame, app, halves[1], cpuGrad, theme);
    }

    private static void drawCompact(Frame frame, App app, Rect area, Color[] cpuGrad, Theme theme) {
        double[] rates = app.getTokenRates().stream().mapToDouble(Double::doubleValue).toArray();
        double tokensPerMin = 0;
        for (int i = Math.max(0, rates.length - 30); i < rates.length; i++) tokensPerMin += rates[i];
        long total = app.getSessions().stream().mapToLong(s -> s.totalTokens()).sum();
        long active = app.getSessions().stream().filter(s -> s.getStatus() == SessionStatus.WORKING).count();

        var line = Line.from(
                Span.styled(" Rate ", Style.create().fg(theme.graphText())),
                Span.styled(fmtTokens((long) tokensPerMin) + "/min", Style.create().fg(gradAt(cpuGrad, 50))),
                Span.styled("  Total ", Style.create().fg(theme.graphText())),
                Span.styled(fmtTokens(total), Style.create().fg(theme.mainFg())),
                Span.styled("  " + active + " active", Style.create().fg(theme.procMisc()))
        );
        frame.renderWidget(Paragraph.builder().text(dev.tamboui.text.Text.from(line)).build(), area);
    }

    private static void drawSparkline(Frame frame, App app, Rect area, Color[] cpuGrad, Theme theme) {
        int availH = area.height();
        int sparkW = Math.max(4, area.width() - 2);
        var lines = new ArrayList<Line>();

        double[] rates = app.getTokenRates().stream().mapToDouble(Double::doubleValue).toArray();
        double maxRate = 1.0;
        for (double r : rates) if (r > maxRate) maxRate = r;
        double[] normalized = new double[rates.length];
        for (int i = 0; i < rates.length; i++) normalized[i] = rates[i] / maxRate;

        double tokensPerMin = 0;
        for (int i = Math.max(0, rates.length - 30); i < rates.length; i++) tokensPerMin += rates[i];
        double currentPct = normalized.length > 0 ? normalized[normalized.length - 1] * 100 : 0;

        lines.add(Line.from(
                Span.styled(" Token Rate", Style.create().fg(theme.graphText())),
                Span.styled("  " + fmtTokens((long) tokensPerMin) + "/min",
                        Style.create().fg(gradAt(cpuGrad, currentPct)))
        ));

        int graphH = Math.max(1, availH - 2);
        var graphRows = brailleGraphMultirow(normalized, sparkW, graphH, cpuGrad, theme.graphText());
        for (var rowSpans : graphRows) {
            var allSpans = new ArrayList<Span>();
            allSpans.add(Span.styled(" ", Style.create()));
            allSpans.addAll(rowSpans);
            lines.add(Line.from(allSpans.toArray(Span[]::new)));
        }

        long totalTokens = app.getSessions().stream().mapToLong(s -> s.totalTokens()).sum();
        lines.add(Line.from(
                Span.styled(" " + fmtTokens(totalTokens), Style.create().fg(theme.mainFg())),
                Span.styled(" total", Style.create().fg(theme.graphText()))
        ));

        frame.renderWidget(Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines.toArray(Line[]::new)))
                .build(), area);
    }

    private static void drawBars(Frame frame, App app, Rect area, Color[] cpuGrad, Theme theme) {
        int barWidth = Math.max(4, Math.min(20, area.width() - 30));
        var lines = new ArrayList<Line>();

        lines.add(Line.from(
                Span.styled("Project        Session  Context", Style.create().fg(theme.mainFg()).bold())
        ));

        for (var s : app.getSessions()) {
            double pct = Math.min(100, s.getContextPercent());
            String warn = s.getContextPercent() >= 90 ? "⚠" : "";
            String sid = s.getSessionId().length() >= 8 ? s.getSessionId().substring(0, 8) : s.getSessionId();

            var spans = new ArrayList<Span>();
            spans.add(Span.styled(
                    String.format("%-14s ", truncateStr(s.getProjectName(), 14)),
                    Style.create().fg(theme.title())));
            spans.add(Span.styled(sid + " ", Style.create().fg(theme.sessionId())));
            spans.addAll(meterBar(pct, barWidth, cpuGrad, theme.meterBg()));
            spans.add(Span.styled(String.format(" %3.0f%%%s", s.getContextPercent(), warn),
                    Style.create().fg(gradAt(cpuGrad, pct))));
            lines.add(Line.from(spans.toArray(Span[]::new)));
        }

        if (app.getSessions().isEmpty()) {
            lines.add(Line.from(Span.styled("no active sessions", Style.create().fg(theme.inactiveFg()))));
        }

        frame.renderWidget(Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines.toArray(Line[]::new)))
                .build(), area);
    }
}
