package dev.abtop.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.abtop.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * UI utilities: braille characters, gradients, meter bars, sparklines, formatting.
 */
public final class UiUtil {

    private UiUtil() {}

    // Braille graph symbols — from btop_draw.cpp, 5x5 lookup [prev*5 + cur]
    public static final String[] BRAILLE_UP = {
            " ", "⢀", "⢠", "⢰", "⢸",
            "⡀", "⣀", "⣠", "⣰", "⣸",
            "⡄", "⣄", "⣤", "⣴", "⣼",
            "⡆", "⣆", "⣦", "⣶", "⣾",
            "⡇", "⣇", "⣧", "⣷", "⣿",
    };

    // Braille dot bits for multi-row graph (left col, right col, bottom-to-top)
    private static final int[] LEFT_BITS = {0x40, 0x04, 0x02, 0x01};
    private static final int[] RIGHT_BITS = {0x80, 0x20, 0x10, 0x08};

    /** Generate 101-step gradient: start → mid → end. */
    public static Color[] makeGradient(int[] start, int[] mid, int[] end) {
        var out = new Color[101];
        for (int i = 0; i <= 100; i++) {
            int[] s, e;
            int offset, range;
            if (i <= 50) { s = start; e = mid; offset = 0; range = 50; }
            else { s = mid; e = end; offset = 50; range = 50; }
            int t = i - offset;
            int r = clamp(s[0] + t * (e[0] - s[0]) / range);
            int g = clamp(s[1] + t * (e[1] - s[1]) / range);
            int b = clamp(s[2] + t * (e[2] - s[2]) / range);
            out[i] = Color.rgb(r, g, b);
        }
        return out;
    }

    public static Color[] makeGradient(Theme.Gradient grad) {
        return makeGradient(grad.start(), grad.mid(), grad.end());
    }

    /** Pick color from gradient at a given percentage. */
    public static Color gradAt(Color[] gradient, double pct) {
        int idx = Math.min(100, Math.max(0, (int) Math.round(pct)));
        return gradient[idx];
    }

    /** Render btop-style meter: filled ■ with gradient, empty ■ with meterBg. */
    public static List<Span> meterBar(double pct, int width, Color[] gradient, Color meterBg) {
        if (width == 0) return List.of();
        double clamped = Math.max(0, Math.min(100, pct));
        int filled = (int) Math.round(clamped / 100.0 * width);
        var spans = new ArrayList<Span>(width);
        for (int i = 0; i < width; i++) {
            if (i < filled) {
                double cellPct = (double) i / width * 100.0;
                spans.add(Span.styled("■", Style.create().fg(gradAt(gradient, cellPct))));
            } else {
                spans.add(Span.styled("■", Style.create().fg(meterBg)));
            }
        }
        return spans;
    }

    /** Meter bar showing remaining: color reflects urgency (green=safe, red=danger). */
    public static List<Span> remainingBar(double remainingPct, int width, Color[] gradient, Color meterBg) {
        if (width == 0) return List.of();
        double clamped = Math.max(0, Math.min(100, remainingPct));
        int filled = (int) Math.round(clamped / 100.0 * width);
        double usedPct = 100.0 - clamped;
        var spans = new ArrayList<Span>(width);
        for (int i = 0; i < width; i++) {
            if (i < filled) {
                spans.add(Span.styled("■", Style.create().fg(gradAt(gradient, usedPct))));
            } else {
                spans.add(Span.styled("■", Style.create().fg(meterBg)));
            }
        }
        return spans;
    }

    /** Render braille sparkline from data points (0.0–1.0). */
    public static List<Span> brailleSparkline(double[] data, int width, Color[] gradient, Color graphText) {
        var spans = new ArrayList<Span>(width);
        if (data.length == 0 || width == 0) {
            for (int i = 0; i < width; i++)
                spans.add(Span.styled(" ", Style.create().fg(graphText)));
            return spans;
        }
        int needed = width * 2;
        double[] sampled = sample(data, needed);

        for (int i = 0; i < width; i++) {
            int prev = (int) Math.round(Math.max(0, Math.min(1, sampled[i * 2])) * 4);
            int cur = (int) Math.round(Math.max(0, Math.min(1, sampled[i * 2 + 1])) * 4);
            int idx = Math.min(24, prev * 5 + cur);
            double pct = sampled[i * 2 + 1] * 100.0;
            spans.add(Span.styled(BRAILLE_UP[idx], Style.create().fg(gradAt(gradient, pct))));
        }
        return spans;
    }

    /** Multi-row braille area graph. Returns one List<Span> per row (top to bottom). */
    public static List<List<Span>> brailleGraphMultirow(double[] data, int width, int height,
                                                         Color[] gradient, Color graphText) {
        var rows = new ArrayList<List<Span>>(height);
        if (height == 0 || width == 0) {
            for (int i = 0; i < height; i++) rows.add(List.of());
            return rows;
        }
        int totalVres = height * 4;
        int needed = width * 2;
        double[] sampled = sample(data, needed);
        int[] heights = new int[sampled.length];
        for (int i = 0; i < sampled.length; i++) {
            heights[i] = (int) Math.round(Math.max(0, Math.min(1, sampled[i])) * totalVres);
        }

        for (int row = 0; row < height; row++) {
            var spans = new ArrayList<Span>(width);
            int invRow = height - 1 - row;
            int baseY = invRow * 4;

            for (int col = 0; col < width; col++) {
                int leftH = heights[col * 2];
                int rightH = heights[col * 2 + 1];
                int pattern = 0;
                for (int d = 0; d < 4; d++) {
                    int yPos = baseY + d;
                    if (leftH > yPos) pattern |= LEFT_BITS[d];
                    if (rightH > yPos) pattern |= RIGHT_BITS[d];
                }
                char ch = (char) (0x2800 + pattern);
                double maxVal = Math.max(sampled[col * 2], sampled[col * 2 + 1]);
                Color color = pattern == 0 ? graphText : gradAt(gradient, maxVal * 100);
                spans.add(Span.styled(String.valueOf(ch), Style.create().fg(color)));
            }
            rows.add(spans);
        }
        return rows;
    }

    // --- Formatting ---

    public static String fmtTokens(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    public static String fmtMemKb(long kb) {
        if (kb >= 1_048_576) return String.format("%.1fG", kb / 1_048_576.0);
        if (kb >= 1024) return (kb / 1024) + "M";
        return kb + "K";
    }

    public static String truncateStr(String s, int max) {
        if (max == 0) return "";
        if (s.codePointCount(0, s.length()) <= max) return s;
        int end = s.offsetByCodePoints(0, max - 1);
        return s.substring(0, end) + "…";
    }

    /** Shorten model name: "claude-opus-4-6" → "opus4.6" */
    public static String shortenModel(String model, boolean is1m) {
        String s = model;
        if (s.startsWith("claude-")) s = s.substring(7);
        s = s.replace("[1m]", "");
        // Find first digit
        int digitPos = -1;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) { digitPos = i; break; }
        }
        String base;
        if (digitPos >= 0) {
            String name = s.substring(0, digitPos);
            if (name.endsWith("-")) name = name.substring(0, name.length() - 1);
            String ver = s.substring(digitPos).replace("-", ".");
            base = name + ver;
        } else {
            base = s;
        }
        return is1m ? base + "[1m]" : base;
    }

    // --- Helpers ---

    private static double[] sample(double[] data, int needed) {
        if (data.length >= needed) {
            var result = new double[needed];
            System.arraycopy(data, data.length - needed, result, 0, needed);
            return result;
        } else {
            var result = new double[needed];
            int pad = needed - data.length;
            System.arraycopy(data, 0, result, pad, data.length);
            return result;
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
