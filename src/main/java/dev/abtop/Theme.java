package dev.abtop;

import dev.tamboui.style.Color;

/**
 * Theme definitions ported from btop-style themes.
 * Each theme defines colors for all UI elements.
 */
public record Theme(
        String name,
        // base
        Color mainFg, Color title, Color hiFg,
        Color selectedBg, Color selectedFg,
        Color inactiveFg, Color graphText, Color meterBg,
        Color procMisc, Color divLine, Color sessionId,
        // semantic
        Color statusFg, Color warningFg,
        // box borders
        Color cpuBox, Color memBox, Color netBox, Color procBox,
        // gradients
        Gradient cpuGrad, Gradient procGrad, Gradient usedGrad,
        Gradient freeGrad, Gradient cachedGrad
) {

    public record Gradient(int[] start, int[] mid, int[] end) {
        public Gradient(int sr, int sg, int sb, int mr, int mg, int mb, int er, int eg, int eb) {
            this(new int[]{sr, sg, sb}, new int[]{mr, mg, mb}, new int[]{er, eg, eb});
        }
    }

    public static final String[] THEME_NAMES = {
            "btop", "dracula", "catppuccin", "tokyo-night", "gruvbox",
            "nord", "high-contrast", "protanopia", "deuteranopia", "tritanopia"
    };

    public static Theme byName(String name) {
        return switch (name) {
            case "btop" -> btop();
            case "dracula" -> dracula();
            case "catppuccin" -> catppuccin();
            case "tokyo-night" -> tokyoNight();
            case "gruvbox" -> gruvbox();
            case "nord" -> nord();
            case "high-contrast" -> highContrast();
            case "protanopia" -> protanopia();
            case "deuteranopia" -> deuteranopia();
            case "tritanopia" -> tritanopia();
            default -> null;
        };
    }

    public static Theme defaultTheme() { return btop(); }

    private static Color rgb(int r, int g, int b) { return Color.rgb(r, g, b); }

    public static Theme btop() {
        return new Theme("btop",
                rgb(204,204,204), rgb(238,238,238), rgb(181,64,64),
                rgb(106,47,47), rgb(238,238,238),
                rgb(64,64,64), rgb(96,96,96), rgb(64,64,64),
                rgb(13,231,86), rgb(48,48,48), rgb(176,160,112),
                rgb(220,76,76), rgb(220,160,50),
                rgb(85,109,89), rgb(108,108,75), rgb(92,88,141), rgb(128,82,82),
                new Gradient(119,202,155, 203,192,108, 220,76,76),
                new Gradient(128,208,163, 220,209,121, 212,84,84),
                new Gradient(89,43,38, 217,98,109, 255,71,105),
                new Gradient(56,79,33, 181,230,133, 220,255,133),
                new Gradient(22,51,80, 116,230,252, 38,197,255));
    }

    public static Theme dracula() {
        return new Theme("dracula",
                rgb(248,248,242), rgb(248,248,242), rgb(255,121,198),
                rgb(68,71,90), rgb(248,248,242),
                rgb(98,114,164), rgb(98,114,164), rgb(68,71,90),
                rgb(80,250,123), rgb(68,71,90), rgb(241,250,140),
                rgb(255,85,85), rgb(241,250,140),
                rgb(139,233,253), rgb(189,147,249), rgb(255,121,198), rgb(255,85,85),
                new Gradient(80,250,123, 241,250,140, 255,85,85),
                new Gradient(80,250,123, 241,250,140, 255,85,85),
                new Gradient(68,71,90, 255,121,198, 255,85,85),
                new Gradient(40,42,54, 80,250,123, 139,233,253),
                new Gradient(40,42,54, 139,233,253, 189,147,249));
    }

    public static Theme catppuccin() {
        return new Theme("catppuccin",
                rgb(205,214,244), rgb(205,214,244), rgb(243,139,168),
                rgb(49,50,68), rgb(205,214,244),
                rgb(108,112,134), rgb(147,153,178), rgb(49,50,68),
                rgb(166,227,161), rgb(69,71,90), rgb(249,226,175),
                rgb(243,139,168), rgb(249,226,175),
                rgb(137,180,250), rgb(203,166,247), rgb(245,194,231), rgb(242,205,205),
                new Gradient(166,227,161, 249,226,175, 243,139,168),
                new Gradient(148,226,213, 249,226,175, 243,139,168),
                new Gradient(49,50,68, 245,194,231, 243,139,168),
                new Gradient(30,30,46, 166,227,161, 148,226,213),
                new Gradient(30,30,46, 137,180,250, 203,166,247));
    }

    public static Theme tokyoNight() {
        return new Theme("tokyo-night",
                rgb(169,177,214), rgb(192,202,245), rgb(247,118,142),
                rgb(41,46,66), rgb(192,202,245),
                rgb(65,72,104), rgb(86,95,137), rgb(59,66,97),
                rgb(158,206,106), rgb(26,27,38), rgb(224,175,104),
                rgb(247,118,142), rgb(224,175,104),
                rgb(125,207,255), rgb(187,154,247), rgb(247,118,142), rgb(255,158,100),
                new Gradient(158,206,106, 224,175,104, 247,118,142),
                new Gradient(115,218,202, 224,175,104, 247,118,142),
                new Gradient(41,46,66, 255,158,100, 247,118,142),
                new Gradient(26,27,38, 158,206,106, 115,218,202),
                new Gradient(26,27,38, 125,207,255, 187,154,247));
    }

    public static Theme gruvbox() {
        return new Theme("gruvbox",
                rgb(235,219,178), rgb(251,241,199), rgb(251,73,52),
                rgb(80,73,69), rgb(251,241,199),
                rgb(124,111,100), rgb(168,153,132), rgb(60,56,54),
                rgb(184,187,38), rgb(50,48,47), rgb(250,189,47),
                rgb(251,73,52), rgb(250,189,47),
                rgb(131,165,152), rgb(211,134,155), rgb(254,128,25), rgb(251,73,52),
                new Gradient(184,187,38, 250,189,47, 251,73,52),
                new Gradient(142,192,124, 250,189,47, 251,73,52),
                new Gradient(60,56,54, 254,128,25, 251,73,52),
                new Gradient(40,40,40, 184,187,38, 142,192,124),
                new Gradient(40,40,40, 131,165,152, 211,134,155));
    }

    public static Theme nord() {
        return new Theme("nord",
                rgb(216,222,233), rgb(236,239,244), rgb(191,97,106),
                rgb(67,76,94), rgb(236,239,244),
                rgb(76,86,106), rgb(76,86,106), rgb(59,66,82),
                rgb(163,190,140), rgb(46,52,64), rgb(235,203,139),
                rgb(191,97,106), rgb(235,203,139),
                rgb(136,192,208), rgb(180,142,173), rgb(208,135,112), rgb(191,97,106),
                new Gradient(163,190,140, 235,203,139, 191,97,106),
                new Gradient(143,188,187, 235,203,139, 191,97,106),
                new Gradient(59,66,82, 208,135,112, 191,97,106),
                new Gradient(46,52,64, 163,190,140, 143,188,187),
                new Gradient(46,52,64, 136,192,208, 180,142,173));
    }

    public static Theme highContrast() {
        return new Theme("high-contrast",
                rgb(255,255,255), rgb(255,255,255), rgb(255,255,0),
                rgb(255,255,0), rgb(0,0,0),
                rgb(128,128,128), rgb(192,192,192), rgb(64,64,64),
                rgb(0,255,255), rgb(96,96,96), rgb(255,255,0),
                rgb(255,255,0), rgb(255,255,0),
                rgb(255,255,255), rgb(255,255,255), rgb(255,255,255), rgb(255,255,255),
                new Gradient(0,255,255, 255,255,255, 255,255,0),
                new Gradient(0,255,255, 255,255,255, 255,255,0),
                new Gradient(32,32,32, 192,192,192, 255,255,0),
                new Gradient(32,32,32, 192,192,192, 0,255,255),
                new Gradient(32,32,32, 128,128,255, 255,255,255));
    }

    public static Theme protanopia() {
        return new Theme("protanopia",
                rgb(220,220,220), rgb(255,255,255), rgb(254,97,0),
                rgb(40,40,60), rgb(255,255,255),
                rgb(96,96,112), rgb(140,140,160), rgb(48,48,64),
                rgb(100,143,255), rgb(48,48,64), rgb(255,176,0),
                rgb(254,97,0), rgb(255,176,0),
                rgb(100,143,255), rgb(120,94,240), rgb(220,38,127), rgb(254,97,0),
                new Gradient(100,143,255, 255,176,0, 254,97,0),
                new Gradient(100,143,255, 255,176,0, 254,97,0),
                new Gradient(40,40,60, 220,38,127, 254,97,0),
                new Gradient(20,20,40, 100,143,255, 255,176,0),
                new Gradient(20,20,40, 120,94,240, 100,143,255));
    }

    public static Theme deuteranopia() {
        return new Theme("deuteranopia",
                rgb(222,222,230), rgb(255,255,255), rgb(255,194,10),
                rgb(30,40,70), rgb(255,255,255),
                rgb(100,108,130), rgb(148,156,178), rgb(42,52,82),
                rgb(26,133,255), rgb(42,52,82), rgb(255,194,10),
                rgb(255,102,0), rgb(255,194,10),
                rgb(26,133,255), rgb(156,106,222), rgb(255,194,10), rgb(255,102,0),
                new Gradient(26,133,255, 255,194,10, 255,102,0),
                new Gradient(26,133,255, 255,194,10, 255,102,0),
                new Gradient(30,40,70, 255,194,10, 255,102,0),
                new Gradient(18,24,48, 26,133,255, 180,210,255),
                new Gradient(18,24,48, 156,106,222, 26,133,255));
    }

    public static Theme tritanopia() {
        return new Theme("tritanopia",
                rgb(224,224,224), rgb(255,255,255), rgb(220,50,47),
                rgb(64,32,40), rgb(255,255,255),
                rgb(120,104,108), rgb(168,152,156), rgb(60,40,48),
                rgb(64,196,208), rgb(48,32,38), rgb(255,140,144),
                rgb(220,50,47), rgb(255,140,144),
                rgb(64,196,208), rgb(198,120,221), rgb(220,50,47), rgb(255,140,144),
                new Gradient(64,196,208, 255,140,144, 220,50,47),
                new Gradient(64,196,208, 255,140,144, 220,50,47),
                new Gradient(40,24,28, 255,140,144, 220,50,47),
                new Gradient(20,28,32, 64,196,208, 180,232,240),
                new Gradient(20,28,32, 198,120,221, 64,196,208));
    }
}
