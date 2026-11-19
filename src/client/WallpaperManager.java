package client;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wallpapers for the chat window. Every picture in the "wallpapers" folder is one wallpaper.
 * next() cycles through them (the last step is "Off"); add() copies a new picture into the folder.
 *
 * Softness: a wallpaper is shrunk to SOFT_WIDTH pixels and stretched back up smoothly when it is
 * painted, so it is never shown at full resolution. A bigger SOFT_WIDTH is sharper, a smaller one blurrier.
 */
public final class WallpaperManager {
    private static final int SOFT_WIDTH = 340;

    private final Path folder;
    private final List<Path> files = new ArrayList<Path>();
    private int index;              // 0 .. files.size()-1 = a wallpaper, files.size() = "Off"
    private BufferedImage soft;     // the shrunk copy of the current wallpaper
    private int softIndex = -1;     // which wallpaper "soft" belongs to

    public WallpaperManager(Path folder, int savedIndex) {
        this.folder = folder;
        scan();
        index = savedIndex >= 0 && savedIndex <= files.size() ? savedIndex : firstWallpaper();
    }

    /** The wallpaper shown before the user has chosen one: "graphite" if it exists, else the first picture. */
    private int firstWallpaper() {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getFileName().toString().startsWith("graphite")) {
                return i;
            }
        }
        return 0;
    }

    /** Reads the folder: every png, jpg, gif or bmp is a wallpaper. */
    private void scan() {
        files.clear();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.matches(".*\\.(png|jpe?g|gif|bmp)")) {
                    files.add(file);
                }
            }
        } catch (IOException missingFolder) {
            // no wallpapers folder: the window simply has no wallpaper
        }
        files.sort(null);
        softIndex = -1;
    }

    /** Copies a picture into the wallpapers folder and makes it the current wallpaper. */
    public void add(Path picture) throws IOException {
        if (ImageIO.read(picture.toFile()) == null) {
            throw new IOException("That file is not a picture this app can read (use PNG, JPG, GIF or BMP).");
        }
        Files.createDirectories(folder);
        Path target = folder.resolve(picture.getFileName().toString());
        for (int copy = 2; Files.exists(target); copy++) {
            target = folder.resolve(copy + "-" + picture.getFileName());
        }
        Files.copy(picture, target);
        scan();
        index = Math.max(0, files.indexOf(target));
    }

    public int index() {
        return index;
    }

    /** Switches to the next wallpaper; after the last one comes "Off", then the first again. */
    public void next() {
        index = (index + 1) % (files.size() + 1);
    }

    private boolean isOff() {
        return index >= files.size();
    }

    /** The current wallpaper's name (file name without the extension), or "Off". */
    public String name() {
        if (isOff()) {
            return "Off";
        }
        String name = files.get(index).getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    /** Paints the current wallpaper over the whole width x height area, cropped to fill it. */
    public void paint(Graphics2D g, int width, int height) {
        BufferedImage picture = isOff() ? null : softPicture();
        if (picture == null || width <= 0 || height <= 0) {
            return;
        }
        double scale = Math.max((double) width / picture.getWidth(), (double) height / picture.getHeight());
        int drawWidth = (int) Math.ceil(picture.getWidth() * scale);
        int drawHeight = (int) Math.ceil(picture.getHeight() * scale);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(picture, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null);
    }

    /** Loads the current wallpaper and shrinks it (once per wallpaper). */
    private BufferedImage softPicture() {
        if (softIndex != index) {
            softIndex = index;
            soft = null;
            try {
                BufferedImage full = ImageIO.read(files.get(index).toFile());
                int height = Math.max(1, full.getHeight() * SOFT_WIDTH / full.getWidth());
                Image shrunk = full.getScaledInstance(SOFT_WIDTH, height, Image.SCALE_AREA_AVERAGING);
                soft = new BufferedImage(SOFT_WIDTH, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = soft.createGraphics();
                g.drawImage(shrunk, 0, 0, null);
                g.dispose();
            } catch (IOException | RuntimeException unreadable) {
                System.err.println("Could not load wallpaper: " + unreadable.getMessage());
            }
        }
        return soft;
    }
}
