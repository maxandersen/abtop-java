package dev.abtop.ui;

import dev.abtop.App;
import dev.abtop.Theme;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.paragraph.Paragraph;

public final class FooterPanel {
    private FooterPanel() {}

    public static void draw(Frame frame, App app, Rect area, Theme theme) {
        var statusMsg = app.getStatusMsg();
        Line line;
        if (statusMsg != null) {
            line = Line.from(Span.styled(" " + statusMsg, Style.create().fg(theme.warningFg())));
        } else {
            line = Line.from(
                    Span.styled(" ↑↓", Style.create().fg(theme.hiFg())),
                    Span.styled(" select  ", Style.create().fg(theme.graphText())),
                    Span.styled("x", Style.create().fg(theme.hiFg())),
                    Span.styled(" kill  ", Style.create().fg(theme.graphText())),
                    Span.styled("X", Style.create().fg(theme.hiFg())),
                    Span.styled(" orphans  ", Style.create().fg(theme.graphText())),
                    Span.styled("t", Style.create().fg(theme.hiFg())),
                    Span.styled(" theme  ", Style.create().fg(theme.graphText())),
                    Span.styled("Enter", Style.create().fg(theme.hiFg())),
                    Span.styled(" jump  ", Style.create().fg(theme.graphText())),
                    Span.styled("q", Style.create().fg(theme.hiFg())),
                    Span.styled(" quit", Style.create().fg(theme.graphText()))
            );
        }
        frame.renderWidget(
                Paragraph.builder().text(dev.tamboui.text.Text.from(line)).build(), area);
    }
}
