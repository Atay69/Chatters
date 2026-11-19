package client;

import shared.ChatProtocol;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The "Messages" list: GroupChat first, then everybody else (online people first). Each row shows
 * the picture, the name, the last message and its time. Clicking a row opens that conversation.
 */
final class ConversationList extends JPanel {
    private static final long serialVersionUID = 1L;

    /** What the list shows besides the names ({@code peer} == null means GroupChat). */
    interface Previews {
        /** The last message of the conversation, or null when there is none. */
        ChatEntry last(String peer);

        /** How many new private messages are waiting in the conversation (0 = none). */
        int unread(String peer);
    }

    private final String self;
    private final Consumer<String> onPick; // called with the person's name, or null for GroupChat
    private final Previews previews;

    ConversationList(String self, Consumer<String> onPick, Previews previews) {
        this.self = self;
        this.onPick = onPick;
        this.previews = previews;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
    }

    /** Rebuilds the list. {@code users} maps each name to online/offline; {@code selected} is the open private chat (null = GroupChat). */
    void update(Map<String, Boolean> users, String selected) {
        removeAll();
        add(row(null, null, selected == null));
        for (boolean online : new boolean[] { true, false }) {
            for (Map.Entry<String, Boolean> user : users.entrySet()) {
                if (user.getValue() == online && !user.getKey().equalsIgnoreCase(self)) {
                    add(row(user.getKey(), online, user.getKey().equalsIgnoreCase(selected)));
                }
            }
        }
        revalidate();
        repaint();
    }

    private JComponent row(String peer, Boolean online, boolean selected) {
        boolean[] hover = { false };
        JPanel row = new JPanel(new BorderLayout(12, 0)) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics graphics) {
                if (selected || hover[0]) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor((selected ? Theme.Role.SELECTED : Theme.Role.CONTROL).color());
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                    g.dispose();
                }
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel picture = new JPanel(new GridBagLayout()); // centres the avatar vertically
        picture.setOpaque(false);
        picture.add(peer == null ? ProfileAvatar.group(44) : new ProfileAvatar(peer, 44).showOnline(online));

        ChatEntry last = previews.last(peer);
        Widgets.Label name = new Widgets.Label(peer == null ? "GroupChat" : ProfileNameFormatter.format(peer), Theme.Role.TEXT);
        name.setFont(new Font("SansSerif", Font.BOLD, 13));
        Widgets.Label preview = new Widgets.Label(describe(last, peer == null), Theme.Role.MUTED);
        preview.setFont(new Font("SansSerif", Font.PLAIN, 11));
        JPanel texts = new JPanel(new GridLayout(2, 1));
        texts.setOpaque(false);
        texts.add(name);
        texts.add(preview);

        Widgets.Label time = new Widgets.Label(last == null ? " " : last.time(), Theme.Role.MUTED);
        time.setFont(new Font("SansSerif", Font.PLAIN, 10));
        JPanel timeHolder = new JPanel(new BorderLayout());
        timeHolder.setOpaque(false);
        timeHolder.add(time, BorderLayout.NORTH);
        int unread = previews.unread(peer);
        if (unread > 0) { // the notification badge: +1, +2 ... up to +9
            JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            badgeRow.setOpaque(false);
            badgeRow.add(new Badge(unread));
            timeHolder.add(badgeRow, BorderLayout.SOUTH);
        }

        row.add(picture, BorderLayout.WEST);
        row.add(texts, BorderLayout.CENTER);
        row.add(timeHolder, BorderLayout.EAST);
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                onPick.accept(peer);
            }

            @Override
            public void mouseEntered(MouseEvent event) {
                hover[0] = true;
                row.repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hover[0] = false;
                row.repaint();
            }
        });
        return row;
    }

    /** A small green pill with "+1" ... "+9" (more than nine still shows "+9"). */
    private static final class Badge extends JLabel {
        private static final long serialVersionUID = 1L;

        Badge(int count) {
            super("+" + Math.min(count, 9), CENTER);
            setFont(new Font("SansSerif", Font.BOLD, 10));
            setForeground(Color.WHITE);
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.ACCENT);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** The one-line preview: "You: hello", "Mia: [Image]" ... */
    private String describe(ChatEntry entry, boolean group) {
        if (entry == null) {
            return "No messages yet";
        }
        String what = entry.type == ChatProtocol.TYPE_IMAGE ? "[Image]"
                : entry.type == ChatProtocol.TYPE_FILE ? "[File] " + entry.text
                : entry.type == ChatProtocol.TYPE_AUDIO ? "[Voice message]" : entry.text.replace('\n', ' ');
        String from = entry.sender.equalsIgnoreCase(self) ? "You: " : group ? ProfileNameFormatter.format(entry.sender) + ": " : "";
        String line = from + what;
        return line.length() <= 30 ? line : line.substring(0, 29) + "…";
    }
}
