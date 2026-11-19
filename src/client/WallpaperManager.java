package client;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wallpapers for the chat window. There are two kinds, and both are in one list:
 *
 *   1. The DEFAULT wallpapers are part of the program itself (src/wallpapers, listed in wallpapers/list.txt),
 *      so they are inside Chatter.jar and every computer has them.
 *   2. The user's OWN wallpapers are the pictures in the folder  <home>/.chatters/wallpapers  of this
 *      computer. "Add wallpaper" copies a picture there.
 *
 * next() cycles through all of them (the last step is "Off"); add() adds a picture of the user.
 *
 * Softness: a wallpaper is shrunk to SOFT_WIDTH pixels and stretched back up smoothly when it is
 * painted, so it is never shown at full resolution. A bigger SOFT_WIDTH is sharper, a smaller one blurrier.
 */
public final class WallpaperManager {
    private static final int SOFT_WIDTH = 340;

    /** One wallpaper: the name shown in the menu and where its picture is. */
    private static final class Wallpaper {
        final String name;
        final URL picture;

        Wallpaper(String name, URL picture) {
            this.name = name;
            this.picture = picture;
        }
    }

    private final Path userFolder;
    private final List<Wallpaper> wallpapers = new ArrayList<Wallpaper>();
    private int index;              // 0 .. size-1 = a wallpaper, size = "Off"
    private BufferedImage soft;     // the shrunk copy of the current wallpaper
    private int softIndex = -1;     // which wallpaper "soft" belongs to

    /** The folder for the user's own wallpapers on this computer. */
    public static Path defaultUserFolder() {
        return Paths.get(System.getProperty("user.home"), ".chatters", "wallpapers");
    }

    public WallpaperManager(Path userFolder, int savedIndex) {
        this.userFolder = userFolder;
        scan();
        index = savedIndex >= 0 && savedIndex <= wallpapers.size() ? savedIndex : firstWallpaper();
    }

    /** The wallpaper shown before the user has chosen one: "graphite" if it exists, else the first picture. */
    private int firstWallpaper() {
        for (int i = 0; i < wallpapers.size(); i++) {
            if (wallpapers.get(i).name.equals("graphite")) {
                return i;
            }
        }
        return 0;
    }

    /** Builds the list: the default wallpapers first, then the user's own ones. */
    private void scan() {
        wallpapers.clear();
        for (String fileName : defaultFileNames()) {
            URL picture = WallpaperManager.class.getResource("/wallpapers/" + fileName);
            if (picture != null) {
                wallpapers.add(new Wallpaper(withoutExtension(fileName), picture));
            }
        }
        List<Path> own = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(userFolder)) {
            for (Path file : stream) {
                if (isPicture(file.getFileName().toString())) {
                    own.add(file);
                }
            }
        } catch (IOException noFolderYet) {
            // the user has not added a wallpaper yet
        }
        own.sort(null);
        for (Path file : own) {
            try {
                wallpapers.add(new Wallpaper(withoutExtension(file.getFileName().toString()), file.toUri().toURL()));
            } catch (IOException impossible) {
                System.err.println("Skipped wallpaper " + file + ": " + impossible.getMessage());
            }
        }
        softIndex = -1;
    }

    /** The file names in wallpapers/list.txt (one per line) = the wallpapers that come with the program. */
    private static List<String> defaultFileNames() {
        List<String> names = new ArrayList<String>();
        try (InputStream list = WallpaperManager.class.getResourceAsStream("/wallpapers/list.txt")) {
            if (list != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(list, StandardCharsets.UTF_8));
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (!line.trim().isEmpty() && isPicture(line.trim())) {
                        names.add(line.trim());
                    }
                }
            }
        } catch (IOException unreadable) {
            System.err.println("Could not read the default wallpapers: " + unreadable.getMessage());
        }
        return names;
    }

    private static boolean isPicture(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|bmp)");
    }

    private static String withoutExtension(String fileName) {
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }

    /** Copies a picture of the user into their wallpaper folder and makes it the current wallpaper. */
    public void add(Path picture) throws IOException {
        if (ImageIO.read(picture.toFile()) == null) {
            throw new IOException("That file is not a picture this app can read (use PNG, JPG, GIF or BMP).");
        }
        Files.createDirectories(userFolder);
        Path target = userFolder.resolve(picture.getFileName().toString());
        for (int copy = 2; Files.exists(target); copy++) {
            target = userFolder.resolve(copy + "-" + picture.getFileName());
        }
        Files.copy(picture, target);
        scan();
        String added = withoutExtension(target.getFileName().toString());
        index = 0;
        for (int i = 0; i < wallpapers.size(); i++) {
            if (wallpapers.get(i).name.equals(added)) {
                index = i;
            }
        }
    }

    public int index() {
        return index;
    }

    /** Switches to the next wallpaper; after the last one comes "Off", then the first again. */
    public void next() {
        index = (index + 1) % (wallpapers.size() + 1);
    }

    private boolean isOff() {
        return index >= wallpapers.size();
    }

    /** The current wallpaper's name (file name without the extension), or "Off". */
    public String name() {
        return isOff() ? "Off" : wallpapers.get(index).name;
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
                BufferedImage full = ImageIO.read(wallpapers.get(index).picture);
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
