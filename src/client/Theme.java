package client;

import java.awt.Color;
import java.awt.Font;

/**
 * All colours of the app, for the dark theme (black glass, white text) and the light theme
 * (white glass, black text). Everything is grey, white or black except the accent colour (Send button, outlines), which each user picks.
 *
 * Widgets ask the Theme for their colour every time they paint, so switching themes only needs a
 * repaint. Most colours are translucent so the wallpaper shows through the glass.
 */
final class Theme {
    /** true = dark theme, false = light theme. */
    static boolean dark = true;

    /** The colours a user can pick for the Send button, the outline of the message box and similar highlights. */
    static final String[] ACCENT_NAMES = { "Green", "Blue", "Purple", "Pink", "Orange", "Teal" };
    private static final Color[] ACCENT_COLORS = {
            new Color(88, 180, 100), new Color(76, 141, 230), new Color(143, 103, 226),
            new Color(226, 96, 148), new Color(232, 146, 62), new Color(60, 183, 186) };

    /** The colour chosen by the signed-in user (an index into ACCENT_NAMES); the window sets it from the saved settings. */
    static int accentIndex = 0;

    /** The highlight colour that is active right now. */
    static Color accent() {
        return ACCENT_COLORS[accentIndex];
    }

    private static final boolean HAS_SEGOE = hasFont("Segoe UI Semibold");

    /**
     * The font of the whole app. Normal text uses "Segoe UI Semibold": a little heavier than regular text
     * (clear to read) but lighter than bold. Bold text (names, titles, buttons) uses Segoe UI Bold.
     * Computers without Segoe UI fall back to the standard sans-serif font.
     */
    static Font font(int style, int size) {
        if (!HAS_SEGOE) {
            return new Font("SansSerif", style, size);
        }
        return style == Font.BOLD ? new Font("Segoe UI", Font.BOLD, size) : new Font("Segoe UI Semibold", Font.PLAIN, size);
    }

    private static boolean hasFont(String family) {
        for (String name : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (name.equalsIgnoreCase(family)) {
                return true;
            }
        }
        return false;
    }

    private Theme() {
    }

    /** A named colour with a dark-theme and a light-theme value. */
    enum Role {
        TEXT(new Color(240, 240, 243), new Color(22, 22, 26)),
        MUTED(new Color(168, 168, 175), new Color(100, 100, 108)),
        ERROR(new Color(255, 125, 125), new Color(190, 40, 55)),
        ONLINE(new Color(98, 208, 125), new Color(30, 150, 70)),

        TITLE_BAR(new Color(18, 18, 21), new Color(236, 236, 240)),           // solid: the bar at the top of the window
        GLASS(new Color(28, 28, 32, 170), new Color(255, 255, 255, 175)),     // the whole window card
        SIDEBAR(new Color(0, 0, 0, 70), new Color(255, 255, 255, 55)),        // extra tint on the left side
        COMPOSER(new Color(255, 255, 255, 12), new Color(0, 0, 0, 12)),       // strip behind the typing bar
        BUBBLE_IN(new Color(64, 64, 70, 185), new Color(255, 255, 255, 215)), // messages from others
        BUBBLE_OUT(new Color(100, 100, 108, 205), new Color(208, 208, 214, 225)), // your messages
        CONTROL(new Color(255, 255, 255, 30), new Color(0, 0, 0, 22)),        // small buttons
        SELECTED(new Color(255, 255, 255, 40), new Color(0, 0, 0, 30)),       // the open conversation in the list
        INPUT(new Color(52, 52, 57), new Color(250, 250, 251)),               // solid: must not show what is behind it
        BORDER(new Color(255, 255, 255, 45), new Color(0, 0, 0, 45));

        private final Color darkColor;
        private final Color lightColor;

        Role(Color darkColor, Color lightColor) {
            this.darkColor = darkColor;
            this.lightColor = lightColor;
        }

        /** The colour for the theme that is active right now. */
        Color color() {
            return dark ? darkColor : lightColor;
        }
    }
}
