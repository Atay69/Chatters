package client;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The toolbar icons (file, image, emoji, microphone, sparkles), drawn with simple lines.
 * They use the theme's text colour every time they paint, so they match the dark and the light theme.
 * Each icon is drawn on a 24 x 24 grid and scaled to the size you ask for.
 */
final class LineIcon implements Icon {
    enum Kind { FILE, IMAGE, EMOJI, MICROPHONE, SPARKLE, MENU }

    private final Kind kind;
    private final int size;

    LineIcon(Kind kind, int size) {
        this.kind = kind;
        this.size = size;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            g.scale(size / 24.0, size / 24.0);
            g.setColor(Theme.Role.TEXT.color());
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case FILE:       drawPaperclip(g); break;
                case IMAGE:      drawPicture(g); break;
                case EMOJI:      drawSmiley(g); break;
                case MICROPHONE: drawMicrophone(g); break;
                case MENU:       drawMenu(g); break;
                default:         drawSparkles(g); break;
            }
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

    // ---------------------------------------------------------------- the five drawings

    private static void drawPaperclip(Graphics2D g) {
        Path2D clip = new Path2D.Double();
        clip.moveTo(15, 8);
        clip.lineTo(15, 15.5);
        clip.curveTo(15, 19.5, 8, 19.5, 8, 15.5); // bottom loop
        clip.lineTo(8, 6.5);
        clip.curveTo(8, 2.5, 13, 2.5, 13, 6.5);   // top loop
        clip.lineTo(13, 14);
        g.draw(clip);
    }

    private static void drawPicture(Graphics2D g) {
        g.draw(new RoundRectangle2D.Double(3.5, 4.5, 17, 15, 4, 4)); // frame
        g.fill(new Ellipse2D.Double(14, 8, 3, 3));                   // sun
        Path2D hills = new Path2D.Double();
        hills.moveTo(4.5, 17);
        hills.lineTo(9, 12.5);
        hills.lineTo(13, 16.5);
        hills.lineTo(15.5, 14);
        hills.lineTo(19.5, 18);
        g.draw(hills);
    }

    private static void drawSmiley(Graphics2D g) {
        g.draw(new Ellipse2D.Double(3, 3, 18, 18));                     // face
        g.fill(new Ellipse2D.Double(8, 9, 2.2, 3));                     // eyes
        g.fill(new Ellipse2D.Double(13.8, 9, 2.2, 3));
        g.draw(new Arc2D.Double(7.5, 7.5, 9, 9, 205, 130, Arc2D.OPEN)); // smile
    }

    private static void drawMicrophone(Graphics2D g) {
        g.draw(new RoundRectangle2D.Double(9, 3, 6, 11, 6, 6));       // head
        g.draw(new Arc2D.Double(6, 5, 12, 12, 180, 180, Arc2D.OPEN)); // holder
        g.draw(new Line2D.Double(12, 17, 12, 20.5));                  // stem
        g.draw(new Line2D.Double(8.5, 20.5, 15.5, 20.5));             // base
    }

    /** The "burger": three lines. */
    private static void drawMenu(Graphics2D g) {
        for (int y = 7; y <= 17; y += 5) {
            g.draw(new Line2D.Double(5, y, 19, y));
        }
    }

    private static void drawSparkles(Graphics2D g) {
        star(g, 10.5, 13.5, 8);  // big
        star(g, 18.5, 5.5, 3.5); // small
    }

    /** A filled four-pointed star. */
    private static void star(Graphics2D g, double cx, double cy, double r) {
        Path2D star = new Path2D.Double();
        star.moveTo(cx, cy - r);
        star.quadTo(cx, cy, cx + r, cy);
        star.quadTo(cx, cy, cx, cy + r);
        star.quadTo(cx, cy, cx - r, cy);
        star.quadTo(cx, cy, cx, cy - r);
        g.fill(star);
    }
}
