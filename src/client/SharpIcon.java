package client;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Crisp icons from big pictures. Swing's built-in getScaledInstance() blurs and jags small
 * icons; this keeps a double-size copy (made with high-quality, stepwise scaling) and paints it
 * with bicubic filtering, so the icon is sharp on normal and high-DPI screens alike.
 */
final class SharpIcon implements Icon {
    private final BufferedImage image;
    private final int size;

    SharpIcon(Image source, int size) {
        this.size = size;
        this.image = scaled(source, size * 2);
    }

    /** A crisp icon from a picture in the project, e.g. "/images/icon.png"; null if the picture is missing. */
    static SharpIcon fromResource(String path, int size) {
        java.net.URL url = SharpIcon.class.getResource(path);
        try {
            return url == null ? null : new SharpIcon(javax.imageio.ImageIO.read(url), size);
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, x, y, size, size, null);
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

    /** The application icon (a picture in the project) in every size the system may ask for; empty if missing. */
    static List<Image> windowIcons(String resourcePath) {
        java.net.URL url = SharpIcon.class.getResource(resourcePath);
        return url == null ? new ArrayList<Image>() : windowIcons(new javax.swing.ImageIcon(url).getImage());
    }

    /** The application icon in every size the operating system may ask for (taskbar, title bar, Alt-Tab). */
    static List<Image> windowIcons(Image source) {
        List<Image> icons = new ArrayList<Image>();
        for (int size : new int[] { 16, 24, 32, 48, 64, 128, 256 }) {
            icons.add(scaled(source, size));
        }
        return icons;
    }

    /** High-quality square resize: halve repeatedly while far above the target, then one bicubic step. */
    static BufferedImage scaled(Image source, int target) {
        BufferedImage current = toBuffered(source);
        while (current.getWidth() / 2 >= target) {
            current = resize(current, current.getWidth() / 2);
        }
        return resize(current, target);
    }

    private static BufferedImage toBuffered(Image source) {
        if (source instanceof BufferedImage) {
            return (BufferedImage) source;
        }
        int width = Math.max(1, source.getWidth(null));
        int height = Math.max(1, source.getHeight(null));
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    private static BufferedImage resize(BufferedImage source, int target) {
        BufferedImage result = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, target, target, null);
        } finally {
            g.dispose();
        }
        return result;
    }
}
