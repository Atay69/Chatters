package client;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Map;
import java.util.function.Consumer;

/** The left side: the "Messages" heading, the list of conversations, and your own profile card. */
final class Sidebar extends Widgets.TintedPanel {
    private static final long serialVersionUID = 1L;

    private final ConversationList conversations;

    Sidebar(String username, Consumer<String> open, ConversationList.Previews previews) {
        super(new BorderLayout(), Theme.Role.SIDEBAR);
        edges("R");
        setPreferredSize(new Dimension(300, 0));
        conversations = new ConversationList(username, open, previews);

        Widgets.Label heading = new Widgets.Label("Messages", Theme.Role.TEXT);
        heading.setFont(new Font("SansSerif", Font.PLAIN, 26));
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
    void update(Map<String, Boolean> people, String activePeer) {
        conversations.update(people, activePeer);
    }

    private JPanel profileCard(String username) {
        Widgets.TintedPanel card = new Widgets.TintedPanel(new BorderLayout(12, 0), Theme.Role.CONTROL, 26);
        card.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 14));
        Widgets.Label name = new Widgets.Label(ProfileNameFormatter.format(username) + "  (you)", Theme.Role.TEXT);
        name.setFont(new Font("SansSerif", Font.BOLD, 13));
        Widgets.Label state = new Widgets.Label("● Online", Theme.Role.ONLINE);
        state.setFont(new Font("SansSerif", Font.PLAIN, 11));
        JPanel text = new JPanel(new BorderLayout(0, 2));
        text.setOpaque(false);
        text.add(name, BorderLayout.NORTH);
        text.add(state, BorderLayout.SOUTH);
        card.add(new ProfileAvatar(username, 40), BorderLayout.WEST);
        card.add(text, BorderLayout.CENTER);

        JPanel holder = new JPanel(new BorderLayout()); // some space around the card
        holder.setOpaque(false);
        holder.setBorder(BorderFactory.createEmptyBorder(6, 16, 18, 16));
        holder.add(card, BorderLayout.CENTER);
        return holder;
    }
}
