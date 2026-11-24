package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.HighlightRule;
import de.pkmd.tailor.settings.Settings;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Set;

/**
 * Rendering parameters of a {@link LogView}.
 *
 * @param lineGap  extra pixels between two log lines
 * @param wrapGap  extra pixels between the rows of one wrapped log line
 */
public record ViewConfig(Font font, int lineGap, int wrapGap, boolean wrap, int tabWidth, boolean lineNumbers,
                         Color foreground, Color background, Color gutterForeground, Color gutterBackground,
                         Color selection, Color currentLine, Color searchHighlight, Color bookmark,
                         Color dimmed, int maxDisplayChars) {

    private static final List<String> PREFERRED_FONTS = List.of("JetBrains Mono", "Cascadia Mono", "Cascadia Code",
            "Fira Code", "Source Code Pro", "Consolas", "SF Mono", "Menlo", "DejaVu Sans Mono", "Liberation Mono",
            "Noto Sans Mono", "Ubuntu Mono", "Courier New");

    private static volatile Set<String> installedFamilies;

    public ViewConfig withWrap(boolean w) {
        return new ViewConfig(font, lineGap, wrapGap, w, tabWidth, lineNumbers, foreground, background,
                gutterForeground, gutterBackground, selection, currentLine, searchHighlight, bookmark, dimmed,
                maxDisplayChars);
    }

    public ViewConfig withLineNumbers(boolean ln) {
        return new ViewConfig(font, lineGap, wrapGap, wrap, tabWidth, ln, foreground, background,
                gutterForeground, gutterBackground, selection, currentLine, searchHighlight, bookmark, dimmed,
                maxDisplayChars);
    }

    /** Builds the configuration from the settings and the current Look and Feel colors. */
    public static ViewConfig from(Settings s) {
        Color fg = color(HighlightRule.parseColor(s.textForeground), "TextArea.foreground", Color.BLACK);
        Color bg = color(HighlightRule.parseColor(s.textBackground), "TextArea.background", Color.WHITE);
        boolean dark = luminance(bg) < 0.5;
        Color gutterBg = mix(bg, fg, 0.05);
        Color gutterFg = mix(bg, fg, 0.45);
        Color sel = color(null, "TextArea.selectionBackground", new Color(0x3874D8));
        sel = new Color(sel.getRed(), sel.getGreen(), sel.getBlue(), dark ? 140 : 110);
        Color current = HighlightRule.parseColor(s.currentLineHighlight);
        if (current == null) {
            current = dark ? new Color(255, 255, 255, 22) : new Color(0, 0, 0, 16);
        }
        Color search = HighlightRule.parseColor(s.searchHighlight);
        if (search == null) {
            search = new Color(0xFFB000);
        }
        Color bookmark = dark ? new Color(0x3D6FB5) : new Color(0x6FA0E8);
        Font font = new Font(resolveFontFamily(s.fontFamily), Font.PLAIN, s.fontSize);
        int maxChars = Math.max(1024, s.maxLineKiB * 1024);
        return new ViewConfig(font, s.lineGap, s.wrapGap, s.wrap, s.tabWidth, s.showLineNumbers, fg, bg, gutterFg,
                gutterBg, sel, current, search, bookmark, mix(bg, fg, 0.5), maxChars);
    }

    /** The requested family if installed, else the first installed preferred monospaced font. */
    public static String resolveFontFamily(String requested) {
        Set<String> installed = installedFamilies();
        if (requested != null && (installed.contains(requested) || isLogical(requested))) {
            return requested;
        }
        for (String f : PREFERRED_FONTS) {
            if (installed.contains(f)) {
                return f;
            }
        }
        return Font.MONOSPACED;
    }

    public static Set<String> installedFamilies() {
        Set<String> s = installedFamilies;
        if (s == null) {
            s = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            installedFamilies = s;
        }
        return s;
    }

    private static boolean isLogical(String f) {
        return f.equals(Font.MONOSPACED) || f.equals(Font.DIALOG) || f.equals(Font.SANS_SERIF)
                || f.equals(Font.SERIF) || f.equals(Font.DIALOG_INPUT);
    }

    private static Color color(Color explicit, String uiKey, Color fallback) {
        if (explicit != null) {
            return explicit;
        }
        Color c = UIManager.getColor(uiKey);
        return c != null ? new Color(c.getRGB()) : fallback;
    }

    static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255;
    }

    static Color mix(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
