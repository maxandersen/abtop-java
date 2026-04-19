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

import static dev.abtop.ui.UiUtil.truncateStr;

public final class PortsPanel {
    private PortsPanel() {}

    public static void draw(Frame frame, App app, Rect area, Theme theme) {
        var block = AbtopRenderer.btopBlock("ports", "⁵", theme.procBox(), theme);
        frame.renderWidget(block, area);

        var inner = AbtopRenderer.innerRect(area);
        var lines = new ArrayList<Line>();
        boolean hasAny = false;

        // Session ports
        for (var s : app.getSessions()) {
            for (var child : s.getChildren()) {
                if (child.port() != null) {
                    hasAny = true;
                    String cmd = child.command().split("\\s+")[0];
                    int slash = cmd.lastIndexOf('/');
                    if (slash >= 0) cmd = cmd.substring(slash + 1);
                    lines.add(Line.from(
                            Span.styled(String.format(" :%d ", child.port()), Style.create().fg(theme.procMisc())),
                            Span.styled(truncateStr(s.getProjectName(), 10) + " ", Style.create().fg(theme.mainFg())),
                            Span.styled(truncateStr(cmd, 12), Style.create().fg(theme.graphText()))
                    ));
                }
            }
        }

        // Orphan ports
        if (!app.getOrphanPorts().isEmpty()) {
            if (hasAny) lines.add(Line.from(Span.styled("", Style.create())));
            lines.add(Line.from(Span.styled(" ORPHAN PORTS", Style.create().fg(theme.warningFg()).bold())));
            for (var o : app.getOrphanPorts()) {
                String cmd = o.command().split("\\s+")[0];
                int slash = cmd.lastIndexOf('/');
                if (slash >= 0) cmd = cmd.substring(slash + 1);
                lines.add(Line.from(
                        Span.styled(String.format(" :%d ", o.port()), Style.create().fg(theme.warningFg())),
                        Span.styled(truncateStr(o.projectName(), 10) + " ", Style.create().fg(theme.mainFg())),
                        Span.styled(truncateStr(cmd, 12), Style.create().fg(theme.graphText()))
                ));
            }
            hasAny = true;
        }

        if (!hasAny) {
            lines.add(Line.from(Span.styled(" —", Style.create().fg(theme.inactiveFg()))));
        }

        frame.renderWidget(Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines.toArray(Line[]::new)))
                .build(), inner);
    }
}
