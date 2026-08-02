package com.nux.tray;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

import com.nux.protocol.UpsStatus;

/**
 * Renders a {@link TrayDisplay} into a transparent square image: the charge number
 * in bold when known, else a lightning bolt; colored by severity (gray when
 * disconnected). Pure Java2D, headless-safe — both tray backends consume it.
 */
public final class TrayIconRenderer {

    private static final Color OK = new Color(0x2da44e);
    private static final Color WARN = new Color(0xd4a72c);
    private static final Color BAD = new Color(0xcf222e);
    private static final Color OFFLINE = new Color(0x8b949e);

    private TrayIconRenderer() {}

    public static BufferedImage render(TrayDisplay display, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(colorFor(display));
        if (display.text() != null) {
            drawText(g, display.text(), size);
        } else {
            drawBolt(g, size);
        }
        g.dispose();
        return image;
    }

    private static Color colorFor(TrayDisplay display) {
        if (!display.connected()) {
            return OFFLINE;
        }
        if (display.severity() == UpsStatus.Severity.OK) {
            return OK;
        }
        return display.severity() == UpsStatus.Severity.WARNING ? WARN : BAD;
    }

    private static void drawText(Graphics2D g, String text, int size) {
        g.setFont(fitted(g, text, size));
        FontMetrics fm = g.getFontMetrics();
        int x = (size - fm.stringWidth(text)) / 2;
        // Center on the digit glyphs (ascent), not the full line box — fonts reserve
        // descent digits never use, which pushed "100" against the top edge.
        int y = (size + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, x, y);
    }

    /** The largest bold font whose rendering of {@code text} fits the square with a margin. */
    static Font fitted(Graphics2D g, String text, int size) {
        int avail = Math.max(4, size - 2); // 1px breathing room per side
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size * 0.82f));
        int width = g.getFontMetrics(font).stringWidth(text);
        if (width > avail) {
            font = font.deriveFont((float) Math.floor(font.getSize2D() * avail / (double) width));
        }
        return font;
    }

    private static void drawBolt(Graphics2D g, int size) {
        // The app icon's bolt (512-grid coordinates), scaled to fill the square.
        double s = size / 512.0;
        Path2D bolt = new Path2D.Double();
        bolt.moveTo(296 * s, 40 * s);
        bolt.lineTo(140 * s, 300 * s);
        bolt.lineTo(231 * s, 300 * s);
        bolt.lineTo(195 * s, 476 * s);
        bolt.lineTo(351 * s, 216 * s);
        bolt.lineTo(260 * s, 216 * s);
        bolt.closePath();
        g.fill(bolt);
        g.setStroke(new BasicStroke((float) (14 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(bolt);
    }
}
