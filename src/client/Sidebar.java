package client;

import shared.ChatProtocol;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/** The left side: the "Messages" heading, the list of conversations, and your own profile card. */
final class Sidebar extends Widgets.TintedPanel {
    private static final long serialVersionUID = 1L;

    private final ChatClient client;
    private int myStatus = ChatProtocol.STATUS_ONLINE; // what I show to the others
    private final ConversationList conversations;

    Sidebar(String username, ChatClient client, Consumer<String> open, ConversationList.Previews previews) {
        super(new BorderLayout(), Theme.Role.SIDEBAR);
        this.client = client;
        edges("R");
        setPreferredSize(new Dimension(300, 0));
        conversations = new ConversationList(username, open, previews);

        Widgets.Label heading = new Widgets.Label("Messages", Theme.Role.TEXT);
        heading.setFont(Theme.font(Font.PLAIN, 26));
        heading.setBorder(BorderFactory.createEmptyBorder(28, 26, 16, 24));

        JPanel listHolder = new JPanel(new BorderLayout());
        listHolder.setOpaque(false);
        listHolder.add(conversations, BorderLayout.NORTH);
        JPanel middle = new JPanel(new BorderLayout());
        middle.setOpaque(false);
        middle.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        middle.add(Widgets.scrollPane(listHolder), BorderLayout.CENTER);

        add(heading, BorderLayout.NORTH);
        add(middle, BorderLayout.CENTER);
        add(profileCard(username), BorderLayout.SOUTH);
    }

    /** Shows the people; {@code activePeer} is the open private chat, or null when GroupChat is open. */
    void update(Map<String, Integer> people, String activePeer) {
        conversations.update(people, activePeer);
    }

    // ---------------------------------------------------------------- your profile picture

    /** Asks for a picture, cuts it to a square, shrinks it to 128 x 128 and sends it to the server, which shows it to everybody. */
    private void choosePicture(ProfileAvatar avatar) {
        Frame window = (Frame) SwingUtilities.getWindowAncestor(avatar);
        FileDialog chooser = new FileDialog(window, "Choose your profile picture", FileDialog.LOAD);
        chooser.setFilenameFilter((directory, name) -> name.toLowerCase().matches(".*\\.(png|jpe?g|gif|bmp)"));
        chooser.setVisible(true);
        if (chooser.getFile() == null) {
            return; // cancelled
        }
        try {
            BufferedImage source = ImageIO.read(new File(chooser.getDirectory(), chooser.getFile()));
            if (source == null) {
                throw new IOException("That file is not a picture.");
            }
            int side = Math.min(source.getWidth(), source.getHeight());
            BufferedImage square = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
            BufferedImage small = SharpIcon.scaled(square, 128);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(small, "png", png);
            client.sendAvatar(png.toByteArray()); // it comes back from the server and appears everywhere, also here
        } catch (IOException error) {
            JOptionPane.showMessageDialog(window, error.getMessage(), "Could not change the picture", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** The four statuses you can choose: Away, Online, Invisible, Do not disturb (with their symbols). */
    private void showStatusMenu(JLabel anchor, Runnable refresh) {
        javax.swing.JPopupMenu menu = Widgets.menu();
        int[] choices = { ChatProtocol.STATUS_AWAY, ChatProtocol.STATUS_ONLINE, ChatProtocol.STATUS_INVISIBLE, ChatProtocol.STATUS_BUSY };
        for (int status : choices) {
            javax.swing.JMenuItem item = Widgets.menuItem(StatusIcon.name(status), new StatusIcon(status, 14));
            item.addActionListener(event -> {
                myStatus = status;
                refresh.run();
                try {
                    client.sendStatus(status); // everybody sees it
                } catch (IOException error) {
                    JOptionPane.showMessageDialog(anchor, error.getMessage(), "Could not change the status", JOptionPane.ERROR_MESSAGE);
                }
            });
            menu.add(item);
        }
        menu.show(anchor, anchor.getWidth() - menu.getPreferredSize().width, -menu.getPreferredSize().height - 6);
    }

    private JPanel profileCard(String username) {
        boolean[] hover = { false };  // true while the mouse is over the card
        Widgets.TintedPanel card = new Widgets.TintedPanel(new BorderLayout(12, 0), Theme.Role.CONTROL, 26) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (hover[0]) { // a little darker under the mouse
                    Graphics2D g = (Graphics2D) graphics;
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(0, 0, 0, 55));
                    g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
                }
            }
        };
        card.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 14));
        Widgets.Label name = new Widgets.Label(ProfileNameFormatter.format(username) + "  (you)", Theme.Role.TEXT);
        name.setFont(Theme.font(Font.BOLD, 13));
        Widgets.Label state = new Widgets.Label("", Theme.Role.ONLINE);
        state.setFont(Theme.font(Font.PLAIN, 11));

        // my status: the small symbol on the right opens the status menu
        JLabel statusDot = new JLabel();
        statusDot.setToolTipText("Set your status");
        statusDot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Runnable showMyStatus = () -> {
            state.setText(StatusIcon.name(myStatus));
            state.setRole(myStatus == ChatProtocol.STATUS_ONLINE ? Theme.Role.ONLINE : Theme.Role.MUTED);
            statusDot.setIcon(new StatusIcon(myStatus, 16));
        };
        showMyStatus.run();
        statusDot.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                showStatusMenu(statusDot, showMyStatus);
            }
        });
        card.add(statusDot, BorderLayout.EAST);
        JPanel text = new JPanel(new BorderLayout(0, 2));
        text.setOpaque(false);
        text.add(name, BorderLayout.NORTH);
        text.add(state, BorderLayout.SOUTH);
        ProfileAvatar avatar = new ProfileAvatar(username, 40);
        avatar.setToolTipText(null); // a tooltip would stop the card from seeing the mouse
        card.add(avatar, BorderLayout.WEST);

        // hover over the card: it darkens and offers to change the picture; click: choose a picture
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hover[0] = true;
                state.setText("Change profile picture");
                state.setRole(Theme.Role.TEXT);
                card.repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (card.contains(event.getPoint())) {
                    return; // only moved onto the status symbol, still inside the card
                }
                hover[0] = false;
                showMyStatus.run();
                card.repaint();
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                choosePicture(avatar);
            }
        });
        card.add(text, BorderLayout.CENTER);

        JPanel holder = new JPanel(new BorderLayout()); // some space around the card
        holder.setOpaque(false);
        holder.setBorder(BorderFactory.createEmptyBorder(6, 16, 18, 16));
        holder.add(card, BorderLayout.CENTER);
        return holder;
    }
}
