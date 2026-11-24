package de.pkmd.tailor.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/** Programmatically drawn icons, so the application needs no image resources. */
final class Icons {

    private Icons() {
    }

    /** Application icon: a page of colored log lines. */
    static List<Image> appIcons() {
        return List.of(appIcon(16), appIcon(32), appIcon(48), appIcon(128));
    }

    private static Image appIcon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float u = s / 16f;
        g.setColor(new Color(0x2B3A4A));
        g.fillRoundRect(0, 0, s, s, Math.round(4 * u), Math.round(4 * u));
        Color[] colors = {new Color(0x9FB3C8), new Color(0xF2C14E), new Color(0x9FB3C8), new Color(0xE05A5A),
                new Color(0x9FB3C8)};
        int[] widths = {10, 8, 11, 9, 6};
        g.setStroke(new BasicStroke(Math.max(1f, 1.4f * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < colors.length; i++) {
            g.setColor(colors[i]);
            float y = (3 + i * 2.5f) * u;
            g.drawLine(Math.round(3 * u), Math.round(y), Math.round((3 + widths[i]) * u), Math.round(y));
        }
        g.dispose();
        return img;
    }

    /** Small color swatch for buttons and table cells; null color draws a "none" marker. */
    static Icon swatch(Color c, int w, int h) {
        return new Icon() {
            @Override
            public void paintIcon(Component comp, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (c != null) {
                    g.setColor(c);
                    g.fillRoundRect(x, y, w - 1, h - 1, 4, 4);
                } else {
                    g.setColor(comp.getForeground());
                    g.drawLine(x + 2, y + h - 3, x + w - 3, y + 2);
                }
                g.setColor(comp.getForeground().darker());
                g.drawRoundRect(x, y, w - 1, h - 1, 4, 4);
                g.dispose();
            }

            @Override
            public int getIconWidth() {
                return w;
            }

            @Override
            public int getIconHeight() {
                return h;
            }
        };
    }
}
