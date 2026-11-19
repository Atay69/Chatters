package client;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;

/**
 * Our own title bar, used instead of the Windows one (the windows are "undecorated").
 * Left: the app icon and the title. Right: minimize, full screen and close buttons
 * (a dialog such as the sign-in window only gets the close button).
 * Drag the bar to move the window.
 */
final class TitleBar extends Widgets.TintedPanel {
    private static final long serialVersionUID = 1L;
    private static final int BAR_HEIGHT = 36;
    private static final int CIRCLE = 22; // diameter of the round buttons

    private enum Kind { MINIMIZE, FULL_SCREEN, CLOSE }

    TitleBar(Window window, String title) {
        super(new BorderLayout(), Theme.Role.TITLE_BAR);
        edges("B");
        setPreferredSize(new Dimension(10, BAR_HEIGHT));

        Widgets.Label label = new Widgets.Label(title, Theme.Role.TEXT);
        label.setFont(Theme.font(Font.BOLD, 12));
        label.setIcon(SharpIcon.fromResource("/images/icon.png", 18));
        label.setIconTextGap(10);
        label.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
        add(label, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(1, 0, 6, 0)); // the buttons side by side
        buttons.setOpaque(false);
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        if (window instanceof Frame) { // only a real window can be minimized or maximized
            Frame frame = (Frame) window;
            buttons.add(new TitleButton(Kind.MINIMIZE, "Minimize", () -> frame.setState(Frame.ICONIFIED)));
            buttons.add(new TitleButton(Kind.FULL_SCREEN, "Full screen", () -> toggleFullScreen(frame)));
        }
        // closing asks the window to close the normal way, so the window's own close code still runs
        buttons.add(new TitleButton(Kind.CLOSE, "Close",
                () -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING))));
        add(buttons, BorderLayout.EAST);

        // dragging the bar moves the window
        MouseAdapter drag = new MouseAdapter() {
            private Point grabbedAt;

            @Override
            public void mousePressed(MouseEvent event) {
                grabbedAt = event.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                Point onScreen = event.getLocationOnScreen();
                window.setLocation(onScreen.x - grabbedAt.x, onScreen.y - grabbedAt.y);
            }
        };
        addMouseListener(drag);
        addMouseMotionListener(drag);
    }

    /** Fills the screen (but leaves the taskbar visible); the same button brings the old size back. */
    private static void toggleFullScreen(Frame frame) {
        boolean full = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        frame.setMaximizedBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
        frame.setExtendedState(full ? Frame.NORMAL : Frame.MAXIMIZED_BOTH);
    }

    /** One small button of the bar; it draws its own symbol and runs {@code action} when clicked. */
    private static final class TitleButton extends JButton {
        private static final long serialVersionUID = 1L;
        private final Kind kind;

        TitleButton(Kind kind, String tooltip, Runnable action) {
            this.kind = kind;
            setPreferredSize(new Dimension(BAR_HEIGHT, BAR_HEIGHT));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setToolTipText(tooltip);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addActionListener(event -> action.run());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean hover = getModel().isRollover();
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                // a round button: grey, or red under the mouse for Close
                g.setColor(hover && kind == Kind.CLOSE ? new Color(218, 91, 103) : Theme.Role.CONTROL.color());
                g.fillOval(cx - CIRCLE / 2, cy - CIRCLE / 2, CIRCLE, CIRCLE);
                if (hover && kind != Kind.CLOSE) { // a little lighter under the mouse
                    g.setColor(Theme.dark ? new Color(255, 255, 255, 34) : new Color(0, 0, 0, 28));
                    g.fillOval(cx - CIRCLE / 2, cy - CIRCLE / 2, CIRCLE, CIRCLE);
                }
                // the symbol inside the circle
                g.setColor(hover && kind == Kind.CLOSE ? Color.WHITE : Theme.Role.TEXT.color());
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (kind == Kind.MINIMIZE) {
                    g.drawLine(cx - 4, cy + 2, cx + 4, cy + 2);
                } else if (kind == Kind.FULL_SCREEN) {
                    g.drawRect(cx - 4, cy - 4, 8, 8);
                } else {
                    g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
                    g.drawLine(cx - 4, cy + 4, cx + 4, cy - 4);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
