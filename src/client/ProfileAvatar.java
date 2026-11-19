package client;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;

/**
 * Round profile picture. For a person it is the anonymous head-and-shoulders silhouette (like the
 * default avatar on Instagram or Facebook); for GroupChat it is a chat bubble. It can show a small
 * green/grey dot for online/offline.
 */
public final class ProfileAvatar extends JLabel {
    private static final long serialVersionUID = 1L;
    private static final Color BACKGROUND = new Color(150, 150, 156);
    private static final Color SILHOUETTE = new Color(245, 245, 247);

    private final boolean group;
    private Boolean online; // null = no dot

    /** The picture of a person. */
    public ProfileAvatar(String name, int size) {
        this(ProfileNameFormatter.format(name), size, false);
    }

    /** The picture of GroupChat. */
    static ProfileAvatar group(int size) {
        return new ProfileAvatar("GroupChat", size, true);
    }

    private ProfileAvatar(String name, int size, boolean group) {
        this.group = group;
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
        setMaximumSize(new Dimension(size, size));
        getAccessibleContext().setAccessibleName("Profile picture for " + name);
        setToolTipText(group ? "GroupChat" : "Profile picture for " + name);
    }

    /** Shows a green (online) or grey (offline) dot at the bottom right; null removes it. */
    ProfileAvatar showOnline(Boolean online) {
        this.online = online;
        return this;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Always a circle at the top-left, even if a layout gives this label a taller box.
            float inset = 1.0f;
            float d = Math.min(getWidth(), getHeight()) - inset * 2;
            Shape circle = new Ellipse2D.Float(inset, inset, d, d);
            g.setColor(BACKGROUND);
            g.fill(circle);
            g.setColor(SILHOUETTE);
            if (group) {
                paintBubble(g, inset, d);
            } else {
                Area person = new Area(new Ellipse2D.Float(inset + d * 0.32f, inset + d * 0.16f, d * 0.36f, d * 0.36f)); // head
                person.add(new Area(new Ellipse2D.Float(inset + d * 0.14f, inset + d * 0.58f, d * 0.72f, d * 0.72f))); // shoulders
                person.intersect(new Area(circle));
                g.fill(person);
            }
            if (online != null) {
                float dot = d * 0.28f;
                g.setColor(new Color(40, 40, 44));
                g.fill(new Ellipse2D.Float(inset + d - dot - 1, inset + d - dot - 1, dot + 2, dot + 2)); // dark ring
                g.setColor(online ? Theme.Role.ONLINE.color() : new Color(120, 120, 126));
                g.fill(new Ellipse2D.Float(inset + d - dot, inset + d - dot, dot, dot));
            }
        } finally {
            g.dispose();
        }
    }

    /** A speech bubble with three dots, for GroupChat. */
    private void paintBubble(Graphics2D g, float inset, float d) {
        g.fillRoundRect(Math.round(inset + d * 0.2f), Math.round(inset + d * 0.26f), Math.round(d * 0.6f), Math.round(d * 0.4f), Math.round(d * 0.2f), Math.round(d * 0.2f));
        g.fillPolygon(new int[] { Math.round(inset + d * 0.34f), Math.round(inset + d * 0.32f), Math.round(inset + d * 0.5f) },
                new int[] { Math.round(inset + d * 0.6f), Math.round(inset + d * 0.78f), Math.round(inset + d * 0.62f) }, 3);
        g.setColor(BACKGROUND);
        for (int i = 0; i < 3; i++) {
            g.fill(new Ellipse2D.Float(inset + d * (0.33f + 0.14f * i), inset + d * 0.4f, d * 0.09f, d * 0.09f));
        }
    }
}
