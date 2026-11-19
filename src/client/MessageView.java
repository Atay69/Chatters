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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.Map;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

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

    /** What this view needs from the window: files stored on the server, and editing / deleting my messages. */
    interface Host {
        /** Loads the whole file into memory (to show a picture or play a voice message); {@code done} gets the bytes. */
        void fetch(ChatEntry entry, Consumer<byte[]> done);

        /** Asks where to save the file and downloads it there. */
        void download(ChatEntry entry);

        /** "Edit message": the text goes back into the message box. */
        void edit(ChatEntry entry);

        /** "Delete message": asks for confirmation, then deletes it for everybody. */
        void delete(ChatEntry entry);
    }

    private static final long MAX_PREVIEW_BYTES = 4L * 1024 * 1024; // bigger pictures are only offered as a download

    private final Host attachments;
    private final Map<ChatEntry, Widgets.AnimatedRow> rows = new IdentityHashMap<ChatEntry, Widgets.AnimatedRow>(); // the row of each message

    MessageView(Frame owner, String self, Host attachments) {
        this.owner = owner;
        this.self = self;
        this.attachments = attachments;
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
        rows.clear();
        previousDay = null;
        list.revalidate();
        list.repaint();
    }

    /** The box at the top of GroupChat. */
    void addWelcome(String greeting) {
        Widgets.TintedPanel box = new Widgets.TintedPanel(new BorderLayout(), Theme.Role.CONTROL, 24);
        box.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        Widgets.Label text = new Widgets.Label("<html><center><b>Welcome to GroupChat</b><br>" + escape(greeting) + "</center></html>", Theme.Role.TEXT);
        text.setFont(Theme.font(Font.PLAIN, 12));
        box.add(text, BorderLayout.CENTER);
        list.add(centered(box));
    }

    /** A plain muted line, e.g. "Start a private conversation with Mia." */
    void addNote(String note) {
        list.add(centered(new Widgets.Label(note, Theme.Role.MUTED)));
    }

    void add(ChatEntry entry) {
        add(entry, false);
    }

    /** Adds a message at the end. {@code animate}: it slides and fades in and the list scrolls down smoothly. */
    void add(ChatEntry entry, boolean animate) {
        LocalDate day = entry.date();
        if (!day.equals(previousDay)) {
            addDayHeading(day);
            previousDay = day;
        }
        Widgets.AnimatedRow row = new Widgets.AnimatedRow(entry.type == ChatProtocol.TYPE_SYSTEM ? systemLine(entry) : bubbleRow(entry));
        rows.put(entry, row);
        list.add(row);
        list.revalidate();
        list.repaint();
        if (animate) {
            row.appear();
        }
        scrollToEnd(animate);
    }

    /** A message was deleted: it fades out and the messages below it slide up. */
    void remove(ChatEntry entry) {
        Widgets.AnimatedRow row = rows.remove(entry);
        if (row != null) {
            row.disappear(() -> {
                list.remove(row);
                list.revalidate();
                list.repaint();
            });
        }
    }

    /** Shows the newest message: with {@code smooth} the list glides down, otherwise it jumps. */
    private void scrollToEnd(boolean smooth) {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            int from = bar.getValue();
            int to = bar.getMaximum() - bar.getVisibleAmount();
            if (!smooth || to <= from) {
                bar.setValue(bar.getMaximum());
                return;
            }
            Animation.run(260, progress -> bar.setValue((int) Math.round(from + (to - from) * progress)), null);
        });
    }

    // ---------------------------------------------------------------- rows

    private void addDayHeading(LocalDate day) {
        String text = day.equals(LocalDate.now()) ? "Today"
                : day.equals(LocalDate.now().minusDays(1)) ? "Yesterday"
                : day.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH));
        Widgets.Label heading = new Widgets.Label(text, Theme.Role.MUTED);
        heading.setFont(Theme.font(Font.PLAIN, 11));
        list.add(centered(heading));
    }

    /** "Sora joined the chat": a quiet centred line instead of a bubble. */
    private JComponent systemLine(ChatEntry entry) {
        Widgets.Label line = new Widgets.Label(entry.text, Theme.Role.MUTED);
        line.setFont(Theme.font(Font.PLAIN, 11));
        return centered(line);
    }

    private JComponent bubbleRow(ChatEntry entry) {
        boolean mine = entry.sender.equalsIgnoreCase(self);
        Widgets.Bubble bubble = new Widgets.Bubble(mine ? Theme.Role.BUBBLE_OUT : Theme.Role.BUBBLE_IN, mine);
        if (!mine && showNames) {
            Widgets.Label name = new Widgets.Label(ProfileNameFormatter.format(entry.sender), Theme.Role.MUTED);
            name.setFont(Theme.font(Font.BOLD, 11));
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(name);
        }
        JComponent content = content(entry);
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.add(content);

        Widgets.Label time = new Widgets.Label((entry.edited ? "edited · " : "") + entry.time(), Theme.Role.MUTED);
        time.setFont(Theme.font(Font.PLAIN, 10));
        JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        timeRow.setOpaque(false);
        timeRow.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0)); // a little air between the text and the time
        timeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        timeRow.add(time);
        bubble.add(timeRow);

        if (mine && entry.id > 0) { // right-click on my own message: Edit / Delete
            bubble.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    showMessageMenu(event, entry);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    showMessageMenu(event, entry);
                }
            });
        }

        Row row = new Row(new BorderLayout());
        row.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        row.add(bubble, mine ? BorderLayout.EAST : BorderLayout.WEST);
        return row;
    }

    /** The menu of my own message (right-click): Edit (text messages only) and Delete. */
    private void showMessageMenu(MouseEvent event, ChatEntry entry) {
        if (!event.isPopupTrigger()) {
            return;
        }
        javax.swing.JPopupMenu menu = Widgets.menu();
        boolean text = (entry.type == ChatProtocol.TYPE_TEXT || entry.type == ChatProtocol.TYPE_PRIVATE) && !entry.stored();
        if (text) {
            menu.add(Widgets.menuItem("Edit message", null)).addActionListener(action -> attachments.edit(entry));
        }
        menu.add(Widgets.menuItem("Delete message", null)).addActionListener(action -> attachments.delete(entry));
        menu.show(event.getComponent(), event.getX(), event.getY());
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
                if (entry.stored()) {
                    attachments.fetch(entry, this::playVoice); // download it, then play
                } else {
                    playVoice(entry.data);
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
        card.add(new JLabel(new LineIcon(LineIcon.Kind.FILE, 30)), BorderLayout.WEST);
        JPanel details = new JPanel(new GridLayout(0, 1, 0, 2));
        details.setOpaque(false);
        Widgets.Label name = new Widgets.Label(shorten(entry.text, 34), Theme.Role.TEXT);
        name.setToolTipText(entry.text);
        details.add(name);
        details.add(new Widgets.Label(ChatEntry.formatSize(entry.fileSize), Theme.Role.MUTED));
        card.add(details, BorderLayout.CENTER);
        Widgets.Button download = actionButton("Download");
        download.addActionListener(event -> saveOrDownload(entry));
        card.add(download, BorderLayout.EAST);
        return card;
    }

    private JComponent imageContent(ChatEntry entry) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JPanel picture = new JPanel(new BorderLayout()); // the picture appears here as soon as it is loaded
        picture.setOpaque(false);
        picture.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(picture);
        if (!entry.stored()) {
            showPicture(picture, entry.data);
        } else if (entry.fileSize <= MAX_PREVIEW_BYTES) {
            attachments.fetch(entry, bytes -> showPicture(picture, bytes));
        }
        JComponent name = textLabel("[Image: " + entry.text + "]");
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(name);
        Widgets.Button download = actionButton("Download image");
        download.setAlignmentX(Component.LEFT_ALIGNMENT);
        download.addActionListener(event -> saveOrDownload(entry));
        content.add(download);
        return content;
    }

    private void showPicture(JPanel holder, byte[] bytes) {
        try {
            BufferedImage picture = ImageIO.read(new ByteArrayInputStream(bytes));
            if (picture == null) {
                return;
            }
            JScrollBar bar = scroll.getVerticalScrollBar();
            boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
            holder.add(new JLabel(new ImageIcon(fitWidth(picture, 300))), BorderLayout.CENTER);
            holder.revalidate();
            list.revalidate();
            list.repaint();
            if (atBottom) { // the picture made the list taller: stay at the newest message
                SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
            }
        } catch (IOException unreadable) {
            // not a readable picture: only the name and the download button remain
        }
    }

    private void playVoice(byte[] wav) {
        try {
            VoiceRecorder.play(wav);
        } catch (Exception error) {
            JOptionPane.showMessageDialog(owner, "Could not play this voice message: " + error.getMessage());
        }
    }

    /** Old attachments carry their bytes and are saved from memory; new ones are downloaded from the server. */
    private void saveOrDownload(ChatEntry entry) {
        if (entry.stored()) {
            attachments.download(entry);
        } else {
            save(entry.text, entry.data);
        }
    }

    /** A text message. Known emoji become pictures; a message of only 1-3 emoji is shown large. */
    private JComponent textLabel(String text) {
        int emojiSize = Emoji.onlyEmoji(text) ? 44 : 22;
        int estimatedWidth = text.length() * 7 + Emoji.count(text) * (emojiSize - 10);
        int width = Math.max(30, Math.min(maxBubbleWidth(), estimatedWidth));
        String html = Emoji.inline(escape(text), emojiSize).replace("\n", "<br>");
        JLabel label = new JLabel("<html><div style='width:" + width + "px'>" + html + "</div></html>");
        label.setFont(Theme.font(Font.PLAIN, 13));
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
