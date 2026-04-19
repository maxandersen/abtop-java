package dev.abtop.ui;

import dev.abtop.App;
import dev.abtop.Theme;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Direction;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;

/**
 * Main renderer: layout priority calculation + panel dispatch.
 */
public final class AbtopRenderer {

    private static final int MIN_WIDTH = 40;
    private static final int MIN_HEIGHT = 10;

    private AbtopRenderer() {}

    public static void draw(Frame frame, App app) {
        var theme = app.getTheme();
        var area = frame.area();
        int w = area.width();
        int h = area.height();

        if (w < MIN_WIDTH || h < MIN_HEIGHT) {
            drawSizeWarning(frame, area, w, h, theme);
            return;
        }

        int available = h - 2; // header + footer

        // Determine how many mid-tier panels fit based on width.
        // 4 panels need ~100 cols, 3 need ~75, 2 need ~50, 1 needs ~25.
        int midPanelCount;
        if (w >= 100)      midPanelCount = 4;  // quota + tokens + projects + ports
        else if (w >= 75)  midPanelCount = 3;  // quota + tokens + projects
        else if (w >= 50)  midPanelCount = 2;  // quota + tokens
        else               midPanelCount = 0;  // too narrow, skip mid row entirely

        // If height is very tight, skip mid row to give sessions more space.
        if (available < 12) midPanelCount = 0;

        int midHIdeal = midPanelCount > 0 ? 8 : 0;
        int sessionsIdeal = Math.max(5, app.getSessions().size() * 2 + 7);
        int contextIdeal = Math.max(5, Math.min(10, app.getSessions().size() + 4));

        int midMin = midPanelCount > 0 ? 6 : 0;
        int midReserved = Math.min(midMin, available);
        int sessionsBudget = Math.max(0, available - midReserved);
        int sessionsH = Math.min(sessionsIdeal, Math.max(Math.min(3, sessionsBudget), sessionsBudget));
        int afterSessions = Math.max(0, available - sessionsH);
        int midH = Math.min(midHIdeal, Math.max(Math.min(midReserved, afterSessions), afterSessions));
        int surplus = Math.max(0, available - sessionsH - midH);
        int contextH = (sessionsH >= sessionsIdeal && surplus >= 5 && w >= 80)
                ? Math.min(contextIdeal, surplus) : 0;

        var constraints = new ArrayList<Constraint>();
        constraints.add(Constraint.length(1)); // header
        if (contextH > 0) constraints.add(Constraint.length(contextH));
        if (midH > 0) constraints.add(Constraint.length(midH));
        constraints.add(Constraint.min(Math.max(3, sessionsH)));
        constraints.add(Constraint.length(1)); // footer

        var chunks = Layout.vertical()
                .constraints(constraints.toArray(Constraint[]::new))
                .split(area).toArray(Rect[]::new);

        int idx = 0;
        HeaderPanel.draw(frame, app, chunks[idx++], theme);

        if (contextH > 0) {
            ContextPanel.draw(frame, app, chunks[idx++], theme);
        }

        if (midH > 0) {
            drawMidPanels(frame, app, chunks[idx++], theme, midPanelCount);
        }

        SessionsPanel.draw(frame, app, chunks[idx++], theme);
        FooterPanel.draw(frame, app, chunks[idx], theme);
    }

    /**
     * Draw 1–4 mid-tier panels depending on available width.
     * Priority order: quota, tokens, projects, ports.
     */
    private static void drawMidPanels(Frame frame, App app, Rect area, Theme theme, int count) {
        var pctConstraints = new Constraint[count];
        int pct = 100 / count;
        for (int i = 0; i < count; i++) pctConstraints[i] = Constraint.percentage(pct);

        var panels = Layout.horizontal()
                .constraints(pctConstraints)
                .split(area).toArray(Rect[]::new);

        // Draw in priority order: quota, tokens, projects, ports
        if (count >= 1) QuotaPanel.draw(frame, app, panels[0], theme);
        if (count >= 2) TokensPanel.draw(frame, app, panels[1], theme);
        if (count >= 3) ProjectsPanel.draw(frame, app, panels[2], theme);
        if (count >= 4) PortsPanel.draw(frame, app, panels[3], theme);
    }

    /** Create btop-style block with notch title. */
    static Block btopBlock(String title, String number, Color boxColor, Theme theme) {
        return Block.builder()
                .title(Title.from(Line.from(
                        Span.styled("┐", Style.create().fg(boxColor)),
                        Span.styled(number, Style.create().fg(theme.hiFg()).bold()),
                        Span.styled(title, Style.create().fg(theme.title()).bold()),
                        Span.styled("┌", Style.create().fg(boxColor)))
                ))
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(boxColor))
                .build();
    }

    /** Inner rect (1px border inset). */
    static Rect innerRect(Rect area) {
        return new Rect(
                area.x() + 1,
                area.y() + 1,
                Math.max(0, area.width() - 2),
                Math.max(0, area.height() - 2));
    }

    private static void drawSizeWarning(Frame frame, Rect area, int w, int h, Theme theme) {
        var lines = new Line[]{
                Line.from(Span.styled("Terminal size too small:", Style.create().fg(theme.mainFg()).bold())),
                Line.from(
                        Span.styled("Width = ", Style.create().fg(theme.mainFg())),
                        Span.styled(String.valueOf(w), Style.create().fg(w < MIN_WIDTH ? Color.RED : Color.GREEN)),
                        Span.styled(" Height = ", Style.create().fg(theme.mainFg())),
                        Span.styled(String.valueOf(h), Style.create().fg(h < MIN_HEIGHT ? Color.RED : Color.GREEN))
                ),
                Line.from(Span.raw("")),
                Line.from(Span.styled("Needed: Width=" + MIN_WIDTH + " Height=" + MIN_HEIGHT,
                        Style.create().fg(theme.mainFg())))
        };
        int y = h / 2 - 2;
        var msgArea = new Rect(0, y, w, Math.min(4, h - y));
        frame.renderWidget(Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines))
                .centered()
                .build(), msgArea);
    }
}
