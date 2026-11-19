package client;

import java.awt.Color;

/**
 * All colours of the app, for the dark theme (black glass, white text) and the light theme
 * (white glass, black text). Everything is grey, white or black except the green Send button.
 *
 * Widgets ask the Theme for their colour every time they paint, so switching themes only needs a
 * repaint. Most colours are translucent so the wallpaper shows through the glass.
 */
final class Theme {
    /** true = dark theme, false = light theme. */
    static boolean dark = true;

    /** The one coloured thing: the Send button, the upload progress bar, the focused input outline. */
    static final Color ACCENT = new Color(88, 180, 100);

    private Theme() {
    }

    /** A named colour with a dark-theme and a light-theme value. */
    enum Role {
        TEXT(new Color(240, 240, 243), new Color(22, 22, 26)),
        MUTED(new Color(168, 168, 175), new Color(100, 100, 108)),
        ERROR(new Color(255, 125, 125), new Color(190, 40, 55)),
        ONLINE(new Color(98, 208, 125), new Color(30, 150, 70)),

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
