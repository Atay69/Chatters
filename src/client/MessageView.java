package client;

import shared.ChatProtocol;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The scrolling list of messages, as chat bubbles: yours on the right, everybody else's on the left.
 * A bubble holds the content (text, a file card, a picture, or a voice message) and the time.
 * A small heading ("Today") is added whenever the day changes, and joins/leaves are plain centred lines.
 * Rows are made with the colours of the current theme, so the window re-creates the list after the
 * theme is switched.
 */
final class MessageView {
    private final Frame owner;
    private final String self;
    private final JPanel list = new Widgets.ListPanel();
    private final JScrollPane scroll;
    private LocalDate previousDay;
    private boolean showNames; // in GroupChat the bubbles of others carry the sender's name

    MessageView(Frame owner, String self) {
        this.owner = owner;
        this.self = self;
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setBorder(BorderFactory.createEmptyBorder(14, 26, 14, 26));
        scroll = Widgets.scrollPane(list);
    }

    JComponent component() {
        return scroll;
    }

    void setShowNames(boolean showNames) {
        this.showNames = showNames;
    }

    void clear() {
        list.removeAll();
        previousDay = null;
        list.revalidate();
        list.repaint();
    }

    /** The box at the top of GroupChat. */
    void addWelcome(String greeting) {
        Widgets.TintedPanel box = new Widgets.TintedPanel(new BorderLayout(), Theme.Role.CONTROL, 24);
        box.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        Widgets.Label text = new Widgets.Label("<html><center><b>Welcome to GroupChat</b><br>" + escape(greeting) + "</center></html>", Theme.Role.TEXT);
        text.setFont(new Font("SansSerif", Font.PLAIN, 12));
        box.add(text, BorderLayout.CENTER);
        list.add(centered(box));
    }

    /** A plain muted line, e.g. "Start a private conversation with Mia." */
    void addNote(String note) {
        list.add(centered(new Widgets.Label(note, Theme.Role.MUTED)));
    }

    void add(ChatEntry entry) {
        LocalDate day = entry.date();
        if (!day.equals(previousDay)) {
            addDayHeading(day);
            previousDay = day;
        }
        list.add(entry.type == ChatProtocol.TYPE_SYSTEM ? systemLine(entry) : bubbleRow(entry));
        list.revalidate();
        list.repaint();
        SwingUtilities.invokeLater(() -> { // jump to the newest message
            JScrollBar bar = scroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    // ---------------------------------------------------------------- rows

    private void addDayHeading(LocalDate day) {
        String text = day.equals(LocalDate.now()) ? "Today"
                : day.equals(LocalDate.now().minusDays(1)) ? "Yesterday"
                : day.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH));
        Widgets.Label heading = new Widgets.Label(text, Theme.Role.MUTED);
        heading.setFont(new Font("SansSerif", Font.PLAIN, 11));
        list.add(centered(heading));
    }

    /** "Sora joined the chat": a quiet centred line instead of a bubble. */
    private JComponent systemLine(ChatEntry entry) {
        Widgets.Label line = new Widgets.Label(entry.text, Theme.Role.MUTED);
        line.setFont(new Font("SansSerif", Font.PLAIN, 11));
        return centered(line);
    }

    private JComponent bubbleRow(ChatEntry entry) {
        boolean mine = entry.sender.equalsIgnoreCase(self);
        Widgets.Bubble bubble = new Widgets.Bubble(mine ? Theme.Role.BUBBLE_OUT : Theme.Role.BUBBLE_IN, mine);
        if (!mine && showNames) {
            Widgets.Label name = new Widgets.Label(ProfileNameFormatter.format(entry.sender), Theme.Role.MUTED);
            name.setFont(new Font("SansSerif", Font.BOLD, 11));
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(name);
        }
        JComponent content = content(entry);
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.add(content);

        Widgets.Label time = new Widgets.Label(entry.time(), Theme.Role.MUTED);
        time.setFont(new Font("SansSerif", Font.PLAIN, 10));
        JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        timeRow.setOpaque(false);
        timeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        timeRow.add(time);
        bubble.add(timeRow);

        Row row = new Row(new BorderLayout());
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        row.add(bubble, mine ? BorderLayout.EAST : BorderLayout.WEST);
        return row;
    }

    private JComponent content(ChatEntry entry) {
        if (entry.type == ChatProtocol.TYPE_FILE) {
            return fileCard(entry);
        }
        if (entry.type == ChatProtocol.TYPE_IMAGE) {
            return imageContent(entry);
        }
        if (entry.type == ChatProtocol.TYPE_AUDIO) {
            JPanel voice = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            voice.setOpaque(false);
            voice.add(new Widgets.Label("Voice message", Theme.Role.TEXT));
            Widgets.Button play = actionButton("Play");
            play.addActionListener(event -> {
                try {
                    VoiceRecorder.play(entry.data);
                } catch (Exception error) {
                    JOptionPane.showMessageDialog(owner, "Could not play this voice message: " + error.getMessage());
                }
            });
            voice.add(play);
            return voice;
        }
        return textLabel(entry.text);
    }

    private JComponent fileCard(ChatEntry entry) {
        Widgets.TintedPanel card = new Widgets.TintedPanel(new BorderLayout(12, 0), Theme.Role.CONTROL, 18);
        card.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 8));
        SharpIcon icon = SharpIcon.fromResource("/images/File.png", 30);
        if (icon != null) {
            card.add(new JLabel(icon), BorderLayout.WEST);
        }
        JPanel details = new JPanel(new GridLayout(0, 1, 0, 2));
        details.setOpaque(false);
        Widgets.Label name = new Widgets.Label(shorten(entry.text, 34), Theme.Role.TEXT);
        name.setToolTipText(entry.text);
        details.add(name);
        details.add(new Widgets.Label(ChatEntry.formatSize(entry.data.length), Theme.Role.MUTED));
        card.add(details, BorderLayout.CENTER);
        Widgets.Button download = actionButton("Download");
        download.addActionListener(event -> save(entry.text, entry.data));
        card.add(download, BorderLayout.EAST);
        return card;
    }

    private JComponent imageContent(ChatEntry entry) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        try {
            BufferedImage picture = ImageIO.read(new ByteArrayInputStream(entry.data));
            if (picture != null) {
                JLabel image = new JLabel(new ImageIcon(fitWidth(picture, 300)));
                image.setAlignmentX(Component.LEFT_ALIGNMENT);
                content.add(image);
            }
        } catch (IOException unreadable) {
            // not a readable picture: only the name and the download button remain
        }
        JComponent name = textLabel("[Image: " + entry.text + "]");
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(name);
        Widgets.Button download = actionButton("Download image");
        download.setAlignmentX(Component.LEFT_ALIGNMENT);
        download.addActionListener(event -> save(entry.text, entry.data));
        content.add(download);
        return content;
    }

    /** A text message. Known emoji become pictures; a message of only 1-3 emoji is shown large. */
    private JComponent textLabel(String text) {
        int emojiSize = Emoji.onlyEmoji(text) ? 44 : 22;
        int estimatedWidth = text.length() * 7 + Emoji.count(text) * (emojiSize - 10);
        int width = Math.max(30, Math.min(maxBubbleWidth(), estimatedWidth));
        String html = Emoji.inline(escape(text), emojiSize).replace("\n", "<br>");
        JLabel label = new JLabel("<html><div style='width:" + width + "px'>" + html + "</div></html>");
        label.setFont(new Font("SansSerif", Font.PLAIN, 13));
        label.setForeground(Theme.Role.TEXT.color());
        return label;
    }

    private int maxBubbleWidth() {
        int chatWidth = scroll.getViewport().getWidth();
        return Math.max(180, Math.min(520, (int) ((chatWidth <= 0 ? 700 : chatWidth) * 0.62)));
    }

    // ---------------------------------------------------------------- helpers

    /** A row whose height is exactly its content, so rows never stretch. */
    private static final class Row extends JPanel {
        private static final long serialVersionUID = 1L;

        Row(LayoutManager layout) {
            super(layout);
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Short.MAX_VALUE, getPreferredSize().height);
        }
    }

    private static JComponent centered(JComponent content) {
        Row row = new Row(new FlowLayout(FlowLayout.CENTER, 0, 4));
        row.add(content);
        return row;
    }

    private Widgets.Button actionButton(String text) {
        Widgets.Button button = new Widgets.Button(text, Theme.Role.CONTROL, Theme.Role.TEXT);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        return button;
    }

    /** Asks where to save, then writes the file. */
    private void save(String fileName, byte[] data) {
        FileDialog chooser = new FileDialog(owner, "Save shared file", FileDialog.SAVE);
        chooser.setFile(fileName);
        chooser.setVisible(true);
        if (chooser.getFile() == null) {
            return;
        }
        try {
            Files.write(new File(chooser.getDirectory(), chooser.getFile()).toPath(), data);
        } catch (IOException error) {
            JOptionPane.showMessageDialog(owner, "Failed to save file: " + error.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static BufferedImage fitWidth(BufferedImage picture, int maxWidth) {
        int width = Math.min(maxWidth, picture.getWidth());
        int height = Math.max(1, picture.getHeight() * width / picture.getWidth());
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(picture, 0, 0, width, height, null);
        g.dispose();
        return result;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String shorten(String text, int maxCharacters) {
        return text.length() <= maxCharacters ? text : text.substring(0, maxCharacters - 1) + "…";
    }
}
