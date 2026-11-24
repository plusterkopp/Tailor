package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.Highlighter;
import de.pkmd.tailor.highlight.Highlighter.Span;
import de.pkmd.tailor.highlight.TextStyle;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A log line prepared for painting: tabs expanded, control characters made visible, styles from
 * all highlight layers resolved per character, and broken into rows if wrapping.
 * Character positions are "display" indices; {@link #toOriginal}/{@link #toDisplay} convert.
 */
final class LineLayout {

    /** Background run in display coordinates. */
    record Background(int start, int end, Color color) {
    }

    final String text;
    final String display;
    /** Display index → original index, null if identical. */
    private final int[] d2o;
    /** Original index → display index (length text.length()+1), null if identical. */
    private final int[] o2d;
    /** Display length belonging to the line itself (excluding a truncation marker). */
    final int contentLength;
    /** Combined style of the line rules, or null. */
    final TextStyle lineStyle;
    final Color lineBackground;
    final List<Background> backgrounds;
    final TextLayout[] rows;
    final int[] rowStart;
    final float width;

    private LineLayout(String text, String display, int[] d2o, int[] o2d, int contentLength, TextStyle lineStyle,
                       List<Background> backgrounds, TextLayout[] rows, int[] rowStart) {
        this.text = text;
        this.display = display;
        this.d2o = d2o;
        this.o2d = o2d;
        this.contentLength = contentLength;
        this.lineStyle = lineStyle;
        this.lineBackground = lineStyle == null ? null : lineStyle.background();
        this.backgrounds = backgrounds;
        this.rows = rows;
        this.rowStart = rowStart;
        float w = 0;
        for (TextLayout r : rows) {
            if (r != null) {
                w = Math.max(w, r.getAdvance());
            }
        }
        this.width = w;
    }

    int rowCount() {
        return rows.length;
    }

    int rowEnd(int r) {
        return r + 1 < rowStart.length ? rowStart[r + 1] : display.length();
    }

    int toDisplay(int orig) {
        orig = Math.max(0, Math.min(orig, text.length()));
        int d = o2d == null ? orig : o2d[orig];
        return Math.min(d, contentLength);
    }

    int toOriginal(int disp) {
        if (disp >= contentLength) {
            return text.length();
        }
        disp = Math.max(0, disp);
        return d2o == null ? Math.min(disp, text.length()) : d2o[disp];
    }

    /** Display index of the insertion point nearest to {@code x} in row {@code r}. */
    int hitTest(int r, float x) {
        TextLayout tl = rows[r];
        if (tl == null) {
            return rowStart[r];
        }
        TextHitInfo hit = tl.hitTestChar(x, 0);
        return rowStart[r] + hit.getInsertionIndex();
    }

    // ------------------------------------------------------------------ construction

    /** Everything needed to lay out lines; one instance per view state. */
    record Params(ViewConfig cfg, FontSet fonts, Highlighter highlighter, Pattern search, float wrapWidth) {
    }

    private static final int STYLE_PLAIN = 0;

    static LineLayout build(String text, long truncatedLength, Params p) {
        ViewConfig cfg = p.cfg();
        // 1. display text
        String src = text.length() > cfg.maxDisplayChars() ? cut(text, cfg.maxDisplayChars()) : text;
        StringBuilder sb = null;
        int[] d2o = null;
        int[] o2d = null;
        boolean hasTab = false;
        boolean hasCtl = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\t') {
                hasTab = true;
            } else if (c < 0x20 || c == 0x7F) {
                hasCtl = true;
            }
        }
        String display = src;
        if (hasTab || hasCtl) {
            sb = new StringBuilder(src.length() + 16);
            if (hasTab) {
                d2o = new int[src.length() * Math.max(1, cfg.tabWidth()) + 1];
                o2d = new int[text.length() + 1];
            }
            int col = 0;
            for (int i = 0; i < src.length(); i++) {
                char c = src.charAt(i);
                if (o2d != null) {
                    o2d[i] = sb.length();
                }
                if (c == '\t') {
                    int n = cfg.tabWidth() - col % cfg.tabWidth();
                    for (int k = 0; k < n; k++) {
                        d2o[sb.length()] = i;
                        sb.append(' ');
                    }
                    col += n;
                    continue;
                }
                if (d2o != null) {
                    d2o[sb.length()] = i;
                }
                sb.append(c < 0x20 ? (char) (0x2400 + c) : c == 0x7F ? '␡' : c);
                col++;
            }
            if (o2d != null) {
                for (int i = src.length(); i <= text.length(); i++) {
                    o2d[i] = sb.length();
                }
            }
            display = sb.toString();
        }
        int contentLength = display.length();
        String marker = null;
        if (truncatedLength > 0 || src.length() < text.length()) {
            long full = truncatedLength > 0 ? truncatedLength : text.length();
            marker = "  … [line truncated, " + Format.bytes(full) + "]";
            display = display + marker;
        }

        // 2. styles per character, bottom layer first
        Highlighter.LineHighlight hl = p.highlighter().apply(src);
        TextStyle lineStyle = hl.lineStyle();
        int n = display.length();
        TextStyle[] styles = new TextStyle[n];
        TextStyle base = lineStyle != null ? lineStyle : TextStyle.EMPTY;
        java.util.Arrays.fill(styles, 0, contentLength, base);
        for (Span s : hl.spans()) {
            paint(styles, s.style(), map(o2d, s.start(), contentLength), map(o2d, s.end(), contentLength));
        }
        if (p.search() != null) {
            TextStyle searchStyle = TextStyle.colors(Color.BLACK, p.cfg().searchHighlight());
            Matcher m = p.search().matcher(src);
            int count = 0;
            while (count < 5000 && m.find()) {
                if (m.end() > m.start()) {
                    paint(styles, searchStyle, map(o2d, m.start(), contentLength), map(o2d, m.end(), contentLength));
                    count++;
                }
            }
        }
        TextStyle markerStyle = new TextStyle(cfg.dimmed(), null, null, true, null, null);
        java.util.Arrays.fill(styles, contentLength, n, markerStyle);

        // 3. attributed text and background runs
        List<Background> backgrounds = new ArrayList<>();
        Color lineBg = lineStyle == null ? null : lineStyle.background();
        if (n == 0) {
            return new LineLayout(text, display, d2o, o2d, contentLength, lineStyle, backgrounds,
                    new TextLayout[]{null}, new int[]{0});
        }
        AttributedString as = new AttributedString(display);
        FontSet fonts = p.fonts();
        int runStart = 0;
        TextStyle runStyle = styles[0];
        int runFont = fonts.fontFor(display.codePointAt(0));
        for (int i = 0; i <= n; ) {
            int cp = i < n ? display.codePointAt(i) : 0;
            int f = i < n ? fonts.fontFor(cp) : -1;
            TextStyle st = i < n ? styles[i] : null;
            if (i == n || st != runStyle || f != runFont) {
                applyRun(as, runStart, i, runStyle, runFont, fonts, cfg);
                Color bg = runStyle.background();
                if (bg != null && !bg.equals(lineBg)) {
                    backgrounds.add(new Background(runStart, i, bg));
                }
                if (i == n) {
                    break;
                }
                runStart = i;
                runStyle = st;
                runFont = f;
            }
            i += Character.charCount(cp);
        }

        // 4. rows
        TextLayout[] rows;
        int[] rowStart;
        if (p.wrapWidth() > 0) {
            List<TextLayout> rl = new ArrayList<>();
            List<Integer> rs = new ArrayList<>();
            LineBreakMeasurer lbm = new LineBreakMeasurer(as.getIterator(), fonts.frc);
            float w = Math.max(p.wrapWidth(), fonts.digitWidth * 4);
            while (lbm.getPosition() < n) {
                rs.add(lbm.getPosition());
                rl.add(lbm.nextLayout(w));
            }
            rows = rl.toArray(new TextLayout[0]);
            rowStart = rs.stream().mapToInt(Integer::intValue).toArray();
        } else {
            rows = new TextLayout[]{new TextLayout(as.getIterator(), fonts.frc)};
            rowStart = new int[]{0};
        }
        return new LineLayout(text, display, d2o, o2d, contentLength, lineStyle, backgrounds, rows, rowStart);
    }

    private static void applyRun(AttributedString as, int start, int end, TextStyle st, int fontIdx, FontSet fonts,
                                 ViewConfig cfg) {
        int style = STYLE_PLAIN;
        if (st.isBold()) {
            style |= Font.BOLD;
        }
        if (st.isItalic()) {
            style |= Font.ITALIC;
        }
        as.addAttribute(TextAttribute.FONT, fonts.font(fontIdx, style), start, end);
        as.addAttribute(TextAttribute.FOREGROUND, st.foreground() != null ? st.foreground() : cfg.foreground(),
                start, end);
        if (st.isUnderline()) {
            as.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, start, end);
        }
        if (st.isStrikethrough()) {
            as.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON, start, end);
        }
    }

    /** Puts {@code layer} on top of {@code styles[from, to)}, sharing results for equal inputs. */
    private static void paint(TextStyle[] styles, TextStyle layer, int from, int to) {
        TextStyle lastBelow = null;
        TextStyle lastResult = null;
        for (int i = from; i < to; i++) {
            TextStyle below = styles[i];
            if (below != lastBelow) {
                lastBelow = below;
                lastResult = layer.over(below);
            }
            styles[i] = lastResult;
        }
    }

    private static int map(int[] o2d, int orig, int contentLength) {
        if (o2d != null) {
            orig = o2d[Math.min(orig, o2d.length - 1)];
        }
        return Math.min(orig, contentLength);
    }

    private static String cut(String s, int max) {
        if (max > 0 && Character.isHighSurrogate(s.charAt(max - 1))) {
            max--;
        }
        return s.substring(0, max);
    }
}
