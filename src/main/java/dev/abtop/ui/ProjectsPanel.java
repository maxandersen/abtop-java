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
import java.util.LinkedHashMap;

import static dev.abtop.ui.UiUtil.truncateStr;

public final class ProjectsPanel {
    private ProjectsPanel() {}

    public static void draw(Frame frame, App app, Rect area, Theme theme) {
        var block = AbtopRenderer.btopBlock("projects", "⁴", theme.cpuBox(), theme);
        frame.renderWidget(block, area);

        var inner = AbtopRenderer.innerRect(area);
        var lines = new ArrayList<Line>();

        // Group sessions by project
        var projects = new LinkedHashMap<String, int[]>(); // name → [added, modified]
        var branches = new LinkedHashMap<String, String>();
        for (var s : app.getSessions()) {
            var stats = projects.computeIfAbsent(s.getProjectName(), k -> new int[2]);
            stats[0] = Math.max(stats[0], s.getGitAdded());
            stats[1] = Math.max(stats[1], s.getGitModified());
            if (!s.getGitBranch().isEmpty()) {
                branches.putIfAbsent(s.getProjectName(), s.getGitBranch());
            }
        }

        for (var entry : projects.entrySet()) {
            String name = entry.getKey();
            int[] stats = entry.getValue();
            String branch = branches.getOrDefault(name, "");

            lines.add(Line.from(Span.styled(
                    " " + truncateStr(name, inner.width() - 2),
                    Style.create().fg(theme.title()).bold())));

            String detail = "";
            if (!branch.isEmpty()) detail += " " + truncateStr(branch, 15);
            if (stats[0] > 0 || stats[1] > 0) {
                if (stats[0] > 0) detail += " +" + stats[0];
                if (stats[1] > 0) detail += " ~" + stats[1];
            } else if (!branch.isEmpty()) {
                detail += " ✓clean";
            }
            if (!detail.isEmpty()) {
                lines.add(Line.from(Span.styled("  " + detail.trim(), Style.create().fg(theme.graphText()))));
            }
        }

        if (projects.isEmpty()) {
            lines.add(Line.from(Span.styled(" —", Style.create().fg(theme.inactiveFg()))));
        }

        frame.renderWidget(Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines.toArray(Line[]::new)))
                .build(), inner);
    }
}
