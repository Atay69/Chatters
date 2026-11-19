package client;

import shared.ChatProtocol;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The small symbol of a status:
 *     Online           green circle
 *     Away             white half moon
 *     Invisible/Offline  grey circle
 *     Do not disturb   red circle with a grey line in the middle
 */
final class StatusIcon implements Icon {
    private static final Color GREY = new Color(120, 120, 126);
    private static final Color RED = new Color(232, 72, 85);
    private static final Color LINE = new Color(205, 205, 210);

    private final int status;
    private final int size;

    StatusIcon(int status, int size) {
        this.status = status;
        this.size = size;
    }

    /** The name shown next to the symbol. */
    static String name(int status) {
        switch (status) {
            case ChatProtocol.STATUS_ONLINE:    return "Online";
            case ChatProtocol.STATUS_AWAY:      return "Away";
            case ChatProtocol.STATUS_BUSY:      return "Do not disturb";
            case ChatProtocol.STATUS_INVISIBLE: return "Invisible";
            default:                            return "Offline";
        }
    }

    /** Paints the symbol inside the square (x, y, d, d). */
    static void paint(Graphics2D g, int status, float x, float y, float d) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (status == ChatProtocol.STATUS_AWAY) {
            // a circle with a bite taken out of it = half moon
            Area moon = new Area(new Ellipse2D.Float(x, y, d, d));
            moon.subtract(new Area(new Ellipse2D.Float(x + d * 0.38f, y - d * 0.12f, d * 0.85f, d * 0.85f)));
            g.setColor(Theme.dark ? Color.WHITE : new Color(90, 90, 98));
            g.fill(moon);
            return;
        }
        g.setColor(status == ChatProtocol.STATUS_ONLINE ? Theme.Role.ONLINE.color()
                : status == ChatProtocol.STATUS_BUSY ? RED : GREY);
        g.fill(new Ellipse2D.Float(x, y, d, d));
        if (status == ChatProtocol.STATUS_BUSY) { // the grey line of "do not disturb"
            g.setColor(LINE);
            g.fill(new RoundRectangle2D.Float(x + d * 0.22f, y + d * 0.40f, d * 0.56f, d * 0.20f, d * 0.2f, d * 0.2f));
        }
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            paint(g, status, x, y, size);
        } finally {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}
