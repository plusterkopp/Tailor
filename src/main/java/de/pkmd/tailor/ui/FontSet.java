package de.pkmd.tailor.ui;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;

/**
 * The view font, its bold/italic variants, and a fallback for characters the font lacks.
 * <p>
 * Physical fonts (unlike Java's logical fonts) have no fallback of their own, so CJK, symbols etc.
 * would render as boxes. Such characters are drawn with the logical "Dialog" font, which the JDK
 * maps to a chain of system fonts covering most of Unicode.
 */
final class FontSet {

    static final int PRIMARY = 0;
    static final int FALLBACK = 1;

    final Font base;
    final FontRenderContext frc;
    final int rowHeight;
    final int baseline;
    final float digitWidth;
    private final Font[][] variants = new Font[2][4];
    /** Per BMP char: 0 unknown, 1 primary can display, 2 needs fallback. */
    private final byte[] bmpCache = new byte[65536];

    FontSet(Font base, FontRenderContext frc) {
        this.base = base;
        this.frc = frc;
        Font fallback = new Font(Font.DIALOG, Font.PLAIN, base.getSize()).deriveFont(base.getSize2D());
        variants[PRIMARY][Font.PLAIN] = base;
        variants[FALLBACK][Font.PLAIN] = fallback;
        LineMetrics lm = base.getLineMetrics("Xgjy|", frc);
        rowHeight = (int) Math.ceil(lm.getAscent() + lm.getDescent() + lm.getLeading());
        baseline = (int) Math.ceil(lm.getAscent() + lm.getLeading() / 2);
        digitWidth = (float) base.getStringBounds("0", frc).getWidth();
    }

    /** @param style combination of {@link Font#BOLD} and {@link Font#ITALIC} */
    Font font(int which, int style) {
        Font f = variants[which][style];
        if (f == null) {
            f = variants[which][Font.PLAIN].deriveFont(style);
            variants[which][style] = f;
        }
        return f;
    }

    /** {@link #PRIMARY} or {@link #FALLBACK} for the code point. */
    int fontFor(int cp) {
        if (cp < 0x80) {
            return PRIMARY;
        }
        if (cp < 0x10000) {
            byte b = bmpCache[cp];
            if (b == 0) {
                b = (byte) (base.canDisplay(cp) ? 1 : 2);
                bmpCache[cp] = b;
            }
            return b == 1 ? PRIMARY : FALLBACK;
        }
        return base.canDisplay(cp) ? PRIMARY : FALLBACK;
    }
}
