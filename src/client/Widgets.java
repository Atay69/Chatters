package client;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * The small reusable building blocks of the window. They read their colours from {@link Theme}
 * while painting, so they follow the dark/light theme without any extra code.
 */
final class Widgets {
    private Widgets() {
    }

    /** A label whose text colour follows the theme. */
    static class Label extends JLabel {
        private Theme.Role role;

        Label(String text, Theme.Role role) {
            super(text);
            this.role = role;
        }

        void setRole(Theme.Role role) {
            this.role = role;
            repaint();
        }

        @Override
        public Color getForeground() {
            return role == null ? super.getForeground() : role.color();
        }
    }

    /**
     * A translucent panel painted in a theme colour, optionally with rounded corners (then it also
     * gets a thin outline) or with thin lines on some sides (see {@link #edges(String)}).
     */
    static class TintedPanel extends JPanel {
        private final Theme.Role fill;
        private final int corner;
        private String edges = "";
        private boolean highlighted;

        TintedPanel(LayoutManager layout, Theme.Role fill, int corner) {
            super(layout);
            this.fill = fill;
            this.corner = corner;
            setOpaque(false);
        }

        TintedPanel(LayoutManager layout, Theme.Role fill) {
            this(layout, fill, 0);
        }

        /** Draws a thin line on the given sides: any of the letters T (top), B (bottom), L (left), R (right). */
        TintedPanel edges(String sides) {
            this.edges = sides;
            return this;
        }

        /** Shows the outline of a rounded panel in the accent colour (used while the input has focus). */
        void setHighlighted(boolean highlighted) {
            this.highlighted = highlighted;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g.setColor(fill.color());
                if (corner > 0) {
                    g.fillRoundRect(0, 0, w - 1, h - 1, corner, corner);
                    g.setColor(highlighted ? Theme.accent() : Theme.Role.BORDER.color());
                    g.drawRoundRect(0, 0, w - 1, h - 1, corner, corner);
                } else {
                    g.fillRect(0, 0, w, h);
                    g.setColor(Theme.Role.BORDER.color());
                    if (edges.contains("T")) g.fillRect(0, 0, w, 1);
                    if (edges.contains("B")) g.fillRect(0, h - 1, w, 1);
                    if (edges.contains("L")) g.fillRect(0, 0, 1, h);
                    if (edges.contains("R")) g.fillRect(w - 1, 0, 1, h);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * The big rounded "glass" card of the window. It paints its own glass colour and cuts everything
     * inside it to the rounded corners.
     */
    static class Glass extends JPanel {
        private final int corner;

        Glass(LayoutManager layout, int corner) {
            super(layout);
            this.corner = corner;
            setOpaque(false);
        }

        @Override
        public void paint(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, corner, corner);
                g.setColor(Theme.Role.GLASS.color());
                g.fill(shape);
                g.setClip(shape);
                super.paint(g);        // everything inside, cut to the rounded corners
                g.setClip(null);
                g.setColor(Theme.Role.BORDER.color());
                g.setStroke(new BasicStroke(1.5f));
                g.draw(shape);         // the outline also hides the rough clip edge
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * A chat bubble with a small tail at its bottom corner: on the right for your own messages,
     * on the left for messages from others. Put the content inside with add().
     */
    static class Bubble extends JPanel {
        private static final int TAIL = 9;
        private final Theme.Role fill;
        private final boolean tailRight;

        Bubble(Theme.Role fill, boolean tailRight) {
            this.fill = fill;
            this.tailRight = tailRight;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 14 + (tailRight ? 0 : TAIL), 7, 14 + (tailRight ? TAIL : 0)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int left = tailRight ? 0 : TAIL;
                int width = w - TAIL;
                java.awt.Polygon tail = tailRight
                        ? new java.awt.Polygon(new int[] { width - 14, w - 1, width - 3 }, new int[] { h - 16, h - 1, h - 1 }, 3)
                        : new java.awt.Polygon(new int[] { TAIL + 14, 0, TAIL + 3 }, new int[] { h - 16, h - 1, h - 1 }, 3);
                g.setColor(fill.color());
                g.fillRoundRect(left, 0, width, h, 24, 24);
                g.fillPolygon(tail);

                // a dim outline around the whole bubble (tail included) in the user's accent colour
                java.awt.geom.Area shape = new java.awt.geom.Area(new RoundRectangle2D.Float(left + 0.5f, 0.5f, width - 1, h - 1, 24, 24));
                shape.add(new java.awt.geom.Area(tail));
                Color accent = Theme.accent();
                g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 110));
                g.setStroke(new BasicStroke(1.3f));
                g.draw(shape);
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * A rounded button. With {@code background == null} it is invisible until the mouse is over it
     * (icon buttons). The text colour follows the theme.
     */
    static class Button extends JButton {
        private final Theme.Role background;
        private Theme.Role textRole;
        private Color backgroundOverride;
        private int corner = 12;
        private final Animation.HoverFade fade = new Animation.HoverFade(this);

        Button(String text, Theme.Role background, Theme.Role textRole) {
            super(text);
            this.background = background;
            this.textRole = textRole;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFont(Theme.font(Font.BOLD, 11));
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addChangeListener(event -> fade.setHover(getModel().isRollover())); // the shading glides in and out
        }

        void setTextRole(Theme.Role role) {
            this.textRole = role;
            repaint();
        }

        /** A fixed colour instead of the themed one (for example red while recording); null = themed again. */
        void setBackgroundOverride(Color color) {
            this.backgroundOverride = color;
            repaint();
        }

        /** How round the corners are (12 by default; the height makes a pill). */
        void setCorner(int corner) {
            this.corner = corner;
        }

        @Override
        public Color getForeground() {
            return textRole == null ? super.getForeground() : textRole.color();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = backgroundOverride != null ? backgroundOverride : background == null ? null : background.color();
                if (fill != null) {
                    g.setColor(fill);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), corner, corner);
                }
                if (isEnabled() && (fade.level() > 0 || getModel().isPressed())) { // hover / press shading (hover fades in and out)
                    int hoverAlpha = Math.round((Theme.dark ? 34 : 28) * fade.level());
                    int alpha = getModel().isPressed() ? (Theme.dark ? 60 : 50) : hoverAlpha;
                    g.setColor(Theme.dark ? new Color(255, 255, 255, alpha) : new Color(0, 0, 0, alpha));
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), corner, corner);
                }
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics); // the icon and the text
        }
    }

    // ---------------------------------------------------------------- popup menus

    /** A popup menu in the colours of the current theme. */
    static javax.swing.JPopupMenu menu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        menu.setBackground(Theme.Role.INPUT.color());
        menu.setBorder(BorderFactory.createLineBorder(Theme.Role.BORDER.color()));
        return menu;
    }

    /** One line of such a menu (the icon may be null); it lights up under the mouse. */
    static javax.swing.JMenuItem menuItem(String text, javax.swing.Icon icon) {
        Color normal = Theme.Role.INPUT.color();
        Color lit = Theme.dark ? new Color(76, 76, 82) : new Color(232, 232, 236);
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(text, icon);
        item.setOpaque(true);
        item.setBackground(normal);
        item.setForeground(Theme.Role.TEXT.color());
        item.setFont(Theme.font(Font.PLAIN, 13));
        item.setIconTextGap(12);
        item.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 22));
        item.addChangeListener(event -> item.setBackground(item.isArmed() ? lit : normal));
        return item;
    }

    /** The same colour, but with its see-through amount multiplied by {@code factor} (0 = invisible, 1 = unchanged). */
    static Color fadeColor(Color color, float factor) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(color.getAlpha() * Math.max(0f, Math.min(1f, factor))));
    }

    /**
     * Wraps one row of the message list so it can slide and fade in when a new message arrives
     * (appear) and fade out while the list closes the gap when a message is deleted (disappear).
     */
    static class AnimatedRow extends JPanel {
        private float alpha = 1f;          // 1 = fully visible
        private int offset;                // pixels the row is still below its final place
        private double heightFactor = 1;   // 1 = full height, 0 = no height left

        AnimatedRow(JComponent row) {
            super(new java.awt.BorderLayout());
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            add(row, java.awt.BorderLayout.CENTER);
        }

        void appear() {
            alpha = 0f;
            offset = 16;
            Animation.run(240, progress -> {
                alpha = (float) progress;
                offset = (int) Math.round((1 - progress) * 16);
                repaint();
            }, null);
        }

        void disappear(Runnable done) {
            Animation.run(260, progress -> {
                alpha = (float) (1 - progress);
                heightFactor = 1 - progress;
                revalidate();
                repaint();
            }, done);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, (int) Math.round(size.height * heightFactor));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Short.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public void paint(Graphics graphics) {
            if (alpha >= 1f && offset == 0) {
                super.paint(graphics);
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setComposite(java.awt.AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
                g.translate(0, offset);
                super.paint(g);
            } finally {
                g.dispose();
            }
        }
    }

    /** A message list that always has the width of the chat area (no sideways scrolling). */
    static class ListPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(40, visible.height - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** The window background: a base colour, the soft wallpaper, and a light wash on top of it. */
    static class WallpaperPanel extends JPanel {
        private final WallpaperManager wallpapers;

        WallpaperPanel(WallpaperManager wallpapers) {
            this.wallpapers = wallpapers;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(Theme.dark ? new Color(24, 24, 27) : new Color(226, 226, 230));
                g.fillRect(0, 0, getWidth(), getHeight());
                wallpapers.paint(g, getWidth(), getHeight());
                g.setColor(Theme.dark ? new Color(0, 0, 0, 60) : new Color(255, 255, 255, 50));
                g.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                g.dispose();
            }
        }
    }

    /** A transparent scroll pane with a thin scroll bar and no sideways scrolling. */
    static JScrollPane scrollPane(Component view) {
        JScrollPane pane = new JScrollPane(view, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setBorder(null);
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        pane.getVerticalScrollBar().setUI(new ThinScrollBarUI());
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(6, 0));
        pane.getVerticalScrollBar().setUnitIncrement(16);
        return pane;
    }

    /** A slim, rounded scroll bar without arrow buttons. */
    private static final class ThinScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(150, 150, 158, 150);
            trackColor = new Color(0, 0, 0, 0);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return noButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return noButton();
        }

        private JButton noButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
            // no track: the thumb floats over the content
        }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(thumbColor);
                g.fillRoundRect(bounds.x + 1, bounds.y + 1, Math.max(2, bounds.width - 2), Math.max(6, bounds.height - 2), 5, 5);
            } finally {
                g.dispose();
            }
        }
    }
}
