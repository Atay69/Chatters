package client;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import java.io.IOException;

/**
 * How the window looks, saved separately for every user on this computer: dark or light theme,
 * accent colour and wallpaper. It also builds the burger button that opens the menu to change them.
 *
 * Saved settings (see Settings):  darkMode.<name>  accent.<name>  wallpaper.<name>
 */
final class Appearance {
    /** The window redraws whatever depends on the theme or the colour. */
    interface Listener {
        void themeChanged();

        void accentChanged();
    }

    private final Frame frame;
    private final String username;
    private final Listener listener;
    private final FileTransfers.Status status;
    private final WallpaperManager wallpapers;

    /** Loads this user's saved choices (must happen before any widget is created: they read the Theme). */
    Appearance(Frame frame, String username, Listener listener, FileTransfers.Status status) {
        this.frame = frame;
        this.username = username;
        this.listener = listener;
        this.status = status;
        Theme.dark = Settings.getBoolean("darkMode." + username, Settings.getBoolean("darkMode", true));
        Theme.accentIndex = Settings.getInt("accent." + username, 0) % Theme.ACCENT_NAMES.length;
        int savedWallpaper = Settings.getInt("wallpaper." + username, Settings.getInt("wallpaper", -1));
        wallpapers = new WallpaperManager(WallpaperManager.defaultUserFolder(), savedWallpaper);
    }

    WallpaperManager wallpapers() {
        return wallpapers;
    }

    /** The burger button (three lines in an outlined square) that opens the menu. */
    JComponent menuButton() {
        Widgets.Button button = new Widgets.Button("", Theme.Role.CONTROL, Theme.Role.TEXT) {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create(); // thin outline around the button
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.Role.BORDER.color());
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g.dispose();
            }
        };
        button.setIcon(new LineIcon(LineIcon.Kind.MENU, 24));
        button.setCorner(14);
        button.setPreferredSize(new Dimension(40, 40));
        button.setToolTipText("Wallpaper, colour and theme");
        button.addActionListener(event -> showMenu(button));
        return button;
    }

    private void showMenu(JComponent anchor) {
        JPopupMenu menu = Widgets.menu();
        menu.add(Widgets.menuItem("Add wallpaper...", null)).addActionListener(event -> addWallpaper());
        menu.add(Widgets.menuItem("Next wallpaper  (" + wallpapers.name() + ")", null)).addActionListener(event -> nextWallpaper());
        menu.add(Widgets.menuItem("Color: " + Theme.ACCENT_NAMES[Theme.accentIndex], null)).addActionListener(event -> nextAccent());
        menu.add(Widgets.menuItem(Theme.dark ? "Light mode" : "Dark mode", null)).addActionListener(event -> toggleTheme());
        menu.show(anchor, anchor.getWidth() - menu.getPreferredSize().width, anchor.getHeight() + 6);
    }

    private void toggleTheme() {
        Theme.dark = !Theme.dark;
        Settings.putBoolean("darkMode." + username, Theme.dark);
        listener.themeChanged(); // the messages are re-created with the new theme's colours
        frame.repaint();
    }

    private void nextAccent() {
        Theme.accentIndex = (Theme.accentIndex + 1) % Theme.ACCENT_NAMES.length;
        Settings.putInt("accent." + username, Theme.accentIndex);
        listener.accentChanged();
        frame.repaint();
    }

    private void nextWallpaper() {
        wallpapers.next();
        wallpaperChanged();
    }

    /** "Add wallpaper": pick a picture; it is saved in your own wallpapers folder (on this computer) and shown right away. */
    private void addWallpaper() {
        File picture = NativeFileDialog.openPicture(frame, "Choose a picture to use as a wallpaper");
        if (picture == null) {
            return;
        }
        try {
            wallpapers.add(picture.toPath());
            wallpaperChanged();
            status.show("Wallpaper added: " + wallpapers.name(), false);
        } catch (IOException error) {
            JOptionPane.showMessageDialog(frame, error.getMessage(), "Could not add wallpaper", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void wallpaperChanged() {
        Settings.putInt("wallpaper." + username, wallpapers.index());
        frame.repaint();
    }
}
