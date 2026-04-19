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
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;

import java.util.ArrayList;
import java.util.List;

import static dev.abtop.ui.UiUtil.*;

public final class SessionsPanel {
    private SessionsPanel() {}

    public static void draw(Frame frame, App app, Rect area, Theme theme) {
        var block = AbtopRenderer.btopBlock("sessions", "⁶", theme.procBox(), theme);
        frame.renderWidget(block, area);

        var inner = AbtopRenderer.innerRect(area);
        if (inner.width() <= 0 || inner.height() <= 0) return;

        int sessionRows = app.getSessions().size() * 2;

        // Very small: just draw the table, no separator or detail
        if (inner.height() < 4) {
            drawTable(frame, app, inner, theme);
            return;
        }

        int detailReserve = Math.min(10, inner.height() / 2);
        int maxTable = Math.max(1, inner.height() - detailReserve);
        int tableH = Math.max(1, Math.min(1 + sessionRows, maxTable));
        // Ensure constraints don't exceed available height
        int sepH = 1;
        int detailH = Math.max(0, inner.height() - tableH - sepH);

        var panelChunks = Layout.vertical()
                .constraints(
                        Constraint.length(tableH),
                        Constraint.length(sepH),
                        Constraint.length(detailH))
                .split(inner).toArray(Rect[]::new);

        // Separator
        if (panelChunks[1].width() > 0 && panelChunks[1].height() > 0) {
            String sep = "─".repeat(panelChunks[1].width());
            frame.renderWidget(Paragraph.builder()
                    .text(dev.tamboui.text.Text.from(
                            Line.from(Span.styled(sep, Style.create().fg(theme.procBox())))))
                    .build(), panelChunks[1]);
        }

        // Session table
        drawTable(frame, app, panelChunks[0], theme);

        // Detail
        if (detailH > 0 && app.getSelected() < app.getSessions().size()) {
            drawDetail(frame, app, panelChunks[2], theme);
        }
    }

    private static void drawTable(Frame frame, App app, Rect area, Theme theme) {
        var procGrad = makeGradient(theme.procGrad());
        int w = area.width();
        boolean showPid = w >= 120;
        boolean showMemory = w >= 100;
        boolean showTurn = w >= 100;

        var rows = new ArrayList<Row>();

        for (int i = 0; i < app.getSessions().size(); i++) {
            var session = app.getSessions().get(i);
            boolean selected = i == app.getSelected();
            String marker = selected ? "►" : " ";

            String agentLabel;
            Color agentColor;
            switch (session.getAgentCli()) {
                case "claude" -> { agentLabel = "*CC"; agentColor = Color.rgb(217, 119, 87); }
                case "codex" -> { agentLabel = ">CD"; agentColor = Color.rgb(122, 157, 255); }
                case "pi" -> { agentLabel = "»PI"; agentColor = Color.rgb(120, 220, 180); }
                case "opencode" -> { agentLabel = "■OC"; agentColor = Color.rgb(255, 165, 0); }
                default -> { agentLabel = session.getAgentCli().substring(0, Math.min(3, session.getAgentCli().length())).toUpperCase(); agentColor = theme.inactiveFg(); }
            }

            String statusIcon;
            Color statusColor;
            switch (session.getStatus()) {
                case WORKING -> { statusIcon = "● Work"; statusColor = theme.procMisc(); }
                case WAITING -> { statusIcon = "◌ Wait"; statusColor = gradAt(procGrad, 50); }
                case DONE -> { statusIcon = "✓ Done"; statusColor = theme.inactiveFg(); }
                default -> { statusIcon = "?"; statusColor = theme.mainFg(); }
            }

            boolean is1m = session.totalTokens() > 200_000 || session.getModel().contains("[1m]");
            String modelShort = shortenModel(session.getModel(), is1m);
            Color ctxColor = gradAt(procGrad, session.getContextPercent());

            Style rowStyle;
            if (selected) {
                rowStyle = Style.create().bg(theme.selectedBg()).fg(theme.selectedFg()).bold();
            } else if (session.getStatus() == SessionStatus.DONE) {
                rowStyle = Style.create().fg(theme.inactiveFg());
            } else {
                rowStyle = Style.create();
            }

            String sid = session.getSessionId().length() >= 8
                    ? session.getSessionId().substring(0, 8) : session.getSessionId();
            String summary = app.sessionSummary(session);

            var cells = new ArrayList<Cell>();
            cells.add(Cell.from(Span.styled(marker, Style.create().fg(theme.hiFg()))));
            cells.add(Cell.from(Span.styled(agentLabel, Style.create().fg(agentColor))));
            if (showPid) cells.add(Cell.from(Span.styled(String.valueOf(session.getPid()), Style.create().fg(theme.inactiveFg()))));
            cells.add(Cell.from(Span.styled(truncateStr(session.getProjectName(), 14), Style.create().fg(theme.title()))));
            cells.add(Cell.from(Span.styled(truncateStr(sid, 9), Style.create().fg(theme.sessionId()))));
            cells.add(Cell.from(Span.styled(summary, Style.create().fg(theme.mainFg()))));
            cells.add(Cell.from(Span.styled(statusIcon, Style.create().fg(statusColor))));
            cells.add(Cell.from(Span.styled(truncateStr(modelShort, 13), Style.create().fg(modelShort.equals("-") ? theme.inactiveFg() : theme.graphText()))));
            cells.add(Cell.from(Span.styled(String.format("%.0f%%", session.getContextPercent()), Style.create().fg(ctxColor))));
            cells.add(Cell.from(Span.styled(fmtTokens(session.totalTokens()), Style.create().fg(theme.mainFg()))));
            if (showMemory) cells.add(Cell.from(Span.styled(session.getMemMb() > 0 ? session.getMemMb() + "M" : "—", Style.create().fg(theme.graphText()))));
            if (showTurn) cells.add(Cell.from(Span.styled(String.valueOf(session.getTurnCount()), Style.create().fg(theme.graphText()))));

            rows.add(Row.from(cells).style(rowStyle).height(1));

            // Task line
            int summaryIdx = showPid ? 5 : 4;
            int totalCols = cells.size();
            var taskCells = new ArrayList<Cell>();
            for (int j = 0; j < totalCols; j++) {
                if (j == summaryIdx) {
                    String task = session.getCurrentTasks().isEmpty() ? "" : session.getCurrentTasks().getLast();
                    taskCells.add(Cell.from(Span.styled("└─ " + task, Style.create().fg(theme.graphText()))));
                } else {
                    taskCells.add(Cell.from(Span.raw("")));
                }
            }
            rows.add(Row.from(taskCells).height(1));
        }

        // Header
        var headerCells = new ArrayList<Cell>();
        headerCells.add(Cell.from(Span.raw("")));
        headerCells.add(Cell.from(Span.styled("AI", Style.create().fg(theme.mainFg()).bold())));
        if (showPid) headerCells.add(Cell.from(Span.styled("Pid", Style.create().fg(theme.mainFg()).bold())));
        headerCells.add(Cell.from(Span.styled("Project", Style.create().fg(theme.mainFg()).bold())));
        headerCells.add(Cell.from(Span.styled("Session", Style.create().fg(theme.mainFg()).bold())));
        headerCells.add(Cell.from(Span.styled("Summary", Style.create().fg(theme.mainFg()).bold())));
        headerCells.add(Cell.from(Span.styled("Status", Style.create().fg(theme.mainFg()).bold())));
        headerCells.add(Cell.from(Span.styled("Model", Style.create().fg(theme.mainFg()).bold())));
        headerCells.add(Cell.from(Span.styled("Context", Style.create().fg(theme.mainFg()).bold())));
        headerCells.add(Cell.from(Span.styled("Tokens", Style.create().fg(theme.mainFg()).bold())));
        if (showMemory) headerCells.add(Cell.from(Span.styled("Memory", Style.create().fg(theme.mainFg()).bold())));
        if (showTurn) headerCells.add(Cell.from(Span.styled("Turn", Style.create().fg(theme.mainFg()).bold())));
        var header = Row.from(headerCells).height(1);

        var widths = new ArrayList<Constraint>();
        widths.add(Constraint.length(1));  // marker
        widths.add(Constraint.length(3));  // agent
        if (showPid) widths.add(Constraint.length(6));
        widths.add(Constraint.length(14)); // project
        widths.add(Constraint.length(9));  // session
        widths.add(Constraint.min(6));     // summary
        widths.add(Constraint.length(8));  // status
        widths.add(Constraint.length(13)); // model
        widths.add(Constraint.length(7));  // context
        widths.add(Constraint.length(7));  // tokens
        if (showMemory) widths.add(Constraint.length(8));
        if (showTurn) widths.add(Constraint.length(4));

        // Scroll offset
        int visibleRows = area.height() - 1;
        int selectedEnd = app.getSelected() * 2 + 2;
        int scrollOffset = Math.max(0, selectedEnd - visibleRows);
        var visibleList = scrollOffset < rows.size()
                ? rows.subList(scrollOffset, rows.size()) : List.<Row>of();

        var table = Table.builder()
                .rows(visibleList)
                .header(header)
                .widths(widths.toArray(Constraint[]::new))
                .build();
        frame.renderStatefulWidget(table, area, new dev.tamboui.widgets.table.TableState());
    }

    private static void drawDetail(Frame frame, App app, Rect area, Theme theme) {
        if (area.height() < 3) return;
        var session = app.getSessions().get(app.getSelected());
        var lines = new ArrayList<Line>();

        // Session header
        lines.add(Line.from(Span.styled(
                " SESSION (►" + session.getSessionId() + " · " + session.getCwd() + ")",
                Style.create().fg(theme.title()).bold())));

        if (!session.getInitialPrompt().isEmpty()) {
            lines.add(Line.from(
                    Span.styled("  task ", Style.create().fg(theme.graphText())),
                    Span.styled(truncateStr(session.getInitialPrompt(), area.width() - 9),
                            Style.create().fg(theme.mainFg()))
            ));
        }

        // Children
        if (!session.getChildren().isEmpty()) {
            lines.add(Line.from(Span.styled(" CHILDREN", Style.create().fg(theme.title()).bold())));
            for (var child : session.getChildren()) {
                String cmd = String.join(" ", List.of(child.command().split("\\s+")).subList(0, Math.min(3, child.command().split("\\s+").length)));
                String portStr = child.port() != null ? " :" + child.port() : "";
                lines.add(Line.from(
                        Span.styled(String.format(" %-6d", child.pid()), Style.create().fg(theme.mainFg())),
                        Span.styled(truncateStr(cmd, area.width() - 18), Style.create().fg(theme.graphText())),
                        Span.styled(String.format(" %5s", fmtMemKb(child.memKb())), Style.create().fg(theme.graphText())),
                        Span.styled(portStr, Style.create().fg(theme.procMisc()))
                ));
            }
        }

        // Subagents
        if (!session.getSubagents().isEmpty()) {
            lines.add(Line.from(Span.styled(" SUBAGENTS", Style.create().fg(theme.title()).bold())));
            for (var sa : session.getSubagents()) {
                String icon = "working".equals(sa.status()) ? "●" : "✓";
                Color fg = "working".equals(sa.status()) ? theme.mainFg() : theme.graphText();
                lines.add(Line.from(
                        Span.styled("  " + icon + " " + truncateStr(sa.name(), 20), Style.create().fg(fg)),
                        Span.styled(String.format(" %6s", fmtTokens(sa.tokens())), Style.create().fg(theme.graphText()))
                ));
            }
        }

        // Footer: memory + version
        lines.add(Line.from(Span.raw("")));
        if ("claude".equals(session.getAgentCli())) {
            lines.add(Line.from(Span.styled(
                    String.format(" MEM %d files · %d/200 lines", session.getMemFileCount(), session.getMemLineCount()),
                    Style.create().fg(theme.graphText()))));
        }
        String effortPart = session.getEffort().isEmpty() ? "" : " · effort: " + session.getEffort();
        lines.add(Line.from(Span.styled(
                String.format(" %s · %s · %d turns%s",
                        session.getVersion(), session.elapsedDisplay(), session.getTurnCount(), effortPart),
                Style.create().fg(theme.inactiveFg()))));

        frame.renderWidget(Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines.toArray(Line[]::new)))
                .build(), area);
    }
}
