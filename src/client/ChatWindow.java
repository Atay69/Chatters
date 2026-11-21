package client;

import shared.AppInfo;
import shared.ChatProtocol;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The chat window, and the program's client entry point.
 *
 * Layout: one rounded glass card on top of the wallpaper:
 *     [ Sidebar: Messages list ] [ header / MessageView / Composer ]
 *
 * This class owns the conversations (GroupChat and one list per private chat), reacts to what the
 * server sends (handlePacket), and switches between conversations. Everything visual is in the
 * small classes it uses: Sidebar, ConversationList, MessageView, Composer, Widgets, Theme.
 */
public class ChatWindow implements Composer.Host, ConversationList.Previews {
    private static final String[] GREETINGS = {
            "Let’s keep the conversation kind.",
            "Good communication starts with respect.",
            "Everyone’s voice belongs here.",
            "A thoughtful conversation starts with listening.",
            "Kind words make better conversations."
    };

    private final String username;
    private final ChatClient client;
    private final boolean aiAvailable;
    private final String greeting = GREETINGS[new Random().nextInt(GREETINGS.length)];
    private final Appearance appearance;       // theme, accent colour, wallpaper (saved per user) and the burger menu
    private final FileTransfers transfers;     // downloads of stored files

    // the conversations
    private final List<ChatEntry> groupEntries = new ArrayList<ChatEntry>();
    private final Map<String, List<ChatEntry>> privateEntries = new HashMap<String, List<ChatEntry>>(); // key: lower-case name
    private final Map<String, Integer> people = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER); // name -> ChatProtocol.STATUS_*
    private final Map<String, Integer> unread = new HashMap<String, Integer>(); // new private messages per person (lower-case name)
    private String activePeer;      // the open private chat, or null when GroupChat is open
    private boolean live;           // false while the saved history is still being replayed

    // the window
    private final JFrame frame = new JFrame(AppInfo.NAME);
    private final MessageView messages;
    private final Composer composer;
    private Sidebar sidebar;
    private final JPanel headerPicture = new JPanel(new BorderLayout());
    private Widgets.Label title;
    private Widgets.Label subtitle;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> start(args));
    }

    /** Shows the sign-in dialog and, when it succeeds, the chat window. */
    private static void start(String[] args) {
        String rememberedName = null;
        try {
            rememberedName = new RememberedProfileStore(Settings.store()).load();
        } catch (RuntimeException noSettings) {
            // no remembered name on this computer
        }
        LoginDialog.Result login = LoginDialog.show(rememberedName, args.length > 0 ? args[0] : "127.0.0.1");
        if (login == null) {
            return;
        }
        ChatWindow window = open(login.client);
        try {
            RememberedProfileStore remembered = new RememberedProfileStore(Settings.store());
            if (login.remember) {
                remembered.rememberAfterSuccessfulConnection(login.client.username());
            } else {
                remembered.forget();
            }
        } catch (Exception error) {
            window.composer.showStatus("Signed in; the account name could not be saved on this device.", true);
        }
    }

    /** Opens the chat window for an already signed-in connection and starts listening to the server. */
    static ChatWindow open(ChatClient client) {
        ChatWindow window = new ChatWindow(client);
        client.setListener(new ChatClient.Listener() {
            @Override
            public void onPacket(ChatProtocol.Packet packet) {
                window.handlePacket(packet);
            }

            @Override
            public void onDisconnected(IOException error) {
                window.connectionLost(error);
            }
        });
        client.startReading();
        window.frame.setVisible(true);
        window.composer.focusInput();
        return window;
    }

    private ChatWindow(ChatClient client) {
        this.client = client;
        this.username = client.username();
        this.aiAvailable = client.aiAvailable();
        // first the user's saved look (the widgets read the Theme while they are created), then the parts of the window
        this.appearance = new Appearance(frame, username, new Appearance.Listener() {
            @Override
            public void themeChanged() {
                refreshPeople();
                refreshMessages();
            }

            @Override
            public void accentChanged() {
                composer.updateAccent();
            }
        }, this::showStatus);
        this.transfers = new FileTransfers(this, this::showStatus);
        this.messages = new MessageView(frame, username, new MessageView.Host() {
            @Override
            public void fetch(ChatEntry entry, java.util.function.Consumer<byte[]> done) {
                transfers.fetch(entry, done);
            }

            @Override
            public void download(ChatEntry entry) {
                transfers.download(entry);
            }

            @Override
            public void edit(ChatEntry entry) {
                composer.startEditing(entry);
            }

            @Override
            public void delete(ChatEntry entry) {
                deleteMessage(entry);
            }
        });
        this.composer = new Composer(this);
        buildWindow();
        showConversation();
    }

    // ---------------------------------------------------------------- building the window

    private void buildWindow() {
        sidebar = new Sidebar(username, client, this::openConversation, this);

        Widgets.Glass card = new Widgets.Glass(new BorderLayout(), 56);
        card.add(sidebar, BorderLayout.WEST);
        card.add(chatPanel(), BorderLayout.CENTER);

        JPanel root = new Widgets.WallpaperPanel(appearance.wallpapers());
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28)); // the wallpaper around the card
        root.add(card, BorderLayout.CENTER);

        // our own title bar on top instead of the Windows one
        JPanel window = new JPanel(new BorderLayout());
        window.add(new TitleBar(frame, AppInfo.NAME), BorderLayout.NORTH);
        window.add(root, BorderLayout.CENTER);

        frame.setUndecorated(true);
        frame.setContentPane(window);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(860, 580));
        frame.setSize(1000, 720); // the card is a little bigger than a phone-style chat
        frame.setLocationRelativeTo(null);
        frame.setIconImages(SharpIcon.windowIcons("/images/icon.png"));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                client.close();
                frame.dispose();
            }
        });
    }

    /** The right part of the card: header, messages, composer. */
    private JPanel chatPanel() {
        title = new Widgets.Label("GroupChat", Theme.Role.TEXT);
        title.setFont(Theme.font(Font.BOLD, 15));
        subtitle = new Widgets.Label(" ", Theme.Role.MUTED);
        subtitle.setFont(Theme.font(Font.PLAIN, 11));
        JPanel titles = new JPanel(new BorderLayout(0, 2));
        titles.setOpaque(false);
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.SOUTH);
        headerPicture.setOpaque(false);
        JPanel who = new JPanel(new BorderLayout(14, 0));
        who.setOpaque(false);
        who.add(headerPicture, BorderLayout.WEST);
        who.add(titles, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(appearance.menuButton()); // one burger button holds all the appearance options

        Widgets.TintedPanel header = new Widgets.TintedPanel(new BorderLayout(), Theme.Role.COMPOSER).edges("B");
        header.setBorder(BorderFactory.createEmptyBorder(14, 26, 14, 26));
        header.add(who, BorderLayout.WEST);
        header.add(buttons, BorderLayout.EAST);

        JPanel chat = new JPanel(new BorderLayout());
        chat.setOpaque(false);
        chat.add(header, BorderLayout.NORTH);
        chat.add(messages.component(), BorderLayout.CENTER);
        chat.add(composer, BorderLayout.SOUTH);
        return chat;
    }

    // ---------------------------------------------------------------- conversations

    /** Opens GroupChat ({@code peer} == null) or the private chat with {@code peer}. */
    private void openConversation(String peer) {
        activePeer = peer;
        if (peer != null) {
            unread.remove(peer.toLowerCase(Locale.ROOT)); // opening the chat clears its notification badge
        }
        showConversation();
    }

    /** Shows the open conversation: the header, the people list, and all its messages. */
    private void showConversation() {
        composer.cancelEditing(); // an unfinished edit belongs to the conversation you are leaving
        composer.clearSuggestions();
        composer.setTextOnly(isAi(activePeer));
        messages.setShowNames(activePeer == null);
        refreshPeople();
        refreshMessages();
    }

    /** Updates everything that depends on who is online: the Messages list and the header. */
    private void refreshPeople() {
        sidebar.update(people, activePeer);
        boolean group = activePeer == null;
        title.setText(group ? "GroupChat" : ProfileNameFormatter.format(activePeer));
        if (group) {
            long online = people.values().stream().filter(status -> status != ChatProtocol.STATUS_OFFLINE).count();
            subtitle.setText(online + " online · " + people.size() + " members");
        } else {
            subtitle.setText(StatusIcon.name(statusOf(activePeer)));
        }
        headerPicture.removeAll();
        headerPicture.add(group ? ProfileAvatar.group(44) : new ProfileAvatar(activePeer, 44).showStatus(statusOf(activePeer)));
        headerPicture.revalidate();
        headerPicture.repaint();
    }

    /** The AI conversation is not a person on the server: it is always online. */
    private static boolean isAi(String peer) {
        return ChatProtocol.AI_NAME.equals(peer);
    }

    private int statusOf(String peer) {
        return isAi(peer) ? ChatProtocol.STATUS_ONLINE : people.getOrDefault(peer, ChatProtocol.STATUS_OFFLINE);
    }

    private void refreshMessages() {
        messages.clear();
        if (activePeer == null) {
            messages.addWelcome(greeting);
            for (ChatEntry entry : groupEntries) {
                messages.add(entry);
            }
            return;
        }
        List<ChatEntry> entries = privateEntries.get(activePeer.toLowerCase(Locale.ROOT));
        if (entries == null || entries.isEmpty()) {
            messages.addNote(isAi(activePeer) ? "Ask the AI anything." : "Start a private conversation with " + ProfileNameFormatter.format(activePeer) + ".");
            return;
        }
        for (ChatEntry entry : entries) {
            messages.add(entry);
        }
    }

    /** The newest real message of a conversation (not a "joined" notice), for the Messages list. */
    @Override
    public ChatEntry last(String peer) {
        List<ChatEntry> entries = peer == null ? groupEntries : privateEntries.get(peer.toLowerCase(Locale.ROOT));
        for (int i = entries == null ? -1 : entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).type != ChatProtocol.TYPE_SYSTEM) {
                return entries.get(i);
            }
        }
        return null;
    }

    /** The "+1 ... +9" badge of a private chat in the Messages list (GroupChat has none). */
    @Override
    public int unread(String peer) {
        return peer == null ? 0 : unread.getOrDefault(peer.toLowerCase(Locale.ROOT), 0);
    }

    /** Stores a new message in its conversation ({@code peer} == null: GroupChat) and shows it if that is open. */
    private void receive(ChatEntry entry, String peer) {
        List<ChatEntry> conversation = peer == null ? groupEntries
                : privateEntries.computeIfAbsent(peer.toLowerCase(Locale.ROOT), key -> new ArrayList<ChatEntry>());
        conversation.add(entry);
        boolean open = peer == null ? activePeer == null : peer.equalsIgnoreCase(activePeer);
        boolean fromOthers = !entry.sender.equalsIgnoreCase(username);
        if (!open && peer != null && live && fromOthers) {
            unread.merge(peer.toLowerCase(Locale.ROOT), 1, Integer::sum); // a new private message you have not seen
        }
        sidebar.update(people, activePeer); // the list shows the newest message and the badge of each conversation
        if (!open) {
            return;
        }
        messages.add(entry, live); // live messages slide in; the saved history just appears
        boolean chatText = entry.type == ChatProtocol.TYPE_TEXT || entry.type == ChatProtocol.TYPE_PRIVATE;
        if (aiAvailable && live && chatText && fromOthers && !isAi(peer)) {
            composer.requestSuggestions(); // the AI may offer reply suggestions
        }
    }

    // ---------------------------------------------------------------- what the server sends

    private void handlePacket(ChatProtocol.Packet packet) {
        byte type = packet.type;
        if (type == ChatProtocol.TYPE_USER_LIST) {
            live = true; // the saved history has been replayed by now
            try {
                people.clear();
                for (ChatProtocol.UserRecord user : ChatProtocol.decodeUserList(packet.data)) {
                    people.put(user.displayName, user.status);
                }
                refreshPeople();
            } catch (IOException error) {
                composer.showStatus("Could not read the user list: " + error.getMessage(), true);
            }
        } else if (type == ChatProtocol.TYPE_USER_PRESENCE) {
            people.put(packet.metadata, packet.data.length > 0 && packet.data[0] != 0 ? ChatProtocol.STATUS_ONLINE : ChatProtocol.STATUS_OFFLINE);
            refreshPeople();
        } else if (type == ChatProtocol.TYPE_AVATAR) {
            ProfileAvatar.setPicture(packet.sender, packet.data); // somebody (maybe me) chose a new picture
            frame.repaint();
        } else if (type == ChatProtocol.TYPE_MSG_EDITED) {
            applyEdit(packet);
        } else if (type == ChatProtocol.TYPE_MSG_DELETED) {
            applyDelete(packet);
        } else if (type == ChatProtocol.TYPE_AI_REPLY) {
            showAiReply(packet);
        } else if (type == ChatProtocol.TYPE_TRANSFER_STATUS) {
            composer.showStatus(packet.metadata, false);
        } else if (type == ChatProtocol.TYPE_PRIVATE) {
            // metadata holds the recipient; the other person of the chat is the "peer"
            String peer = packet.sender.equalsIgnoreCase(username) ? packet.metadata : packet.sender;
            String text = new String(packet.data, StandardCharsets.UTF_8);
            receive(new ChatEntry(packet.sender, packet.timestamp, text, type, packet.data, packet.id), peer);
        } else if (type == ChatProtocol.TYPE_PRIVATE_AUDIO) {
            String peer = packet.sender.equalsIgnoreCase(username) ? packet.metadata : packet.sender;
            // shown like a GroupChat voice message (type AUDIO), but kept in the private conversation
            receive(new ChatEntry(packet.sender, packet.timestamp, "voice_message.wav", ChatProtocol.TYPE_AUDIO, packet.data, packet.id), peer);
        } else if (type == ChatProtocol.TYPE_TEXT || type == ChatProtocol.TYPE_IMAGE || type == ChatProtocol.TYPE_FILE
                || type == ChatProtocol.TYPE_AUDIO || type == ChatProtocol.TYPE_SYSTEM) {
            receive(new ChatEntry(packet.sender, packet.timestamp, packet.metadata, type, packet.data, packet.id), null);
        }
    }

    /** An answer from the AI assistant: the first character of the metadata says what it is, the rest is the text. */
    private void showAiReply(ChatProtocol.Packet packet) {
        String metadata = packet.metadata;
        char kind = metadata.isEmpty() ? ChatProtocol.AI_ASK : metadata.charAt(0);
        String text = metadata.isEmpty() ? "" : metadata.substring(1);
        if (kind == ChatProtocol.AI_SUGGEST) {
            composer.showSuggestions(text.split("\n"));
        } else if (kind == ChatProtocol.AI_COMPLETE) {
            composer.append(text);
        } else if (kind == ChatProtocol.AI_CHAT) { // a message from the AI in its own conversation
            receive(new ChatEntry(ChatProtocol.AI_NAME, packet.timestamp, text, ChatProtocol.TYPE_PRIVATE, new byte[0]), ChatProtocol.AI_NAME);
        } else {
            notice(text);
        }
    }

    // ---------------------------------------------------------------- editing and deleting messages

    /** The messages of the conversation an edit/delete packet belongs to (null if we never saw that conversation). */
    private List<ChatEntry> entriesOf(ChatProtocol.Packet packet) {
        if (packet.metadata.isEmpty()) {
            return groupEntries;
        }
        return privateEntries.get(peerOf(packet).toLowerCase(Locale.ROOT));
    }

    /** The other person of a private message packet (metadata = recipient, sender = author). */
    private String peerOf(ChatProtocol.Packet packet) {
        return packet.sender.equalsIgnoreCase(username) ? packet.metadata : packet.sender;
    }

    private boolean isOpen(ChatProtocol.Packet packet) {
        return packet.metadata.isEmpty() ? activePeer == null : peerOf(packet).equalsIgnoreCase(activePeer);
    }

    /** A message was edited (by me or by the other person): swap its text and show the "edited" label. */
    private void applyEdit(ChatProtocol.Packet packet) {
        List<ChatEntry> entries = entriesOf(packet);
        if (entries == null || packet.id == 0) {
            return;
        }
        String text = new String(packet.data, StandardCharsets.UTF_8);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == packet.id) {
                entries.set(i, entries.get(i).withText(text));
                sidebar.update(people, activePeer); // the list may show this message as its preview
                if (isOpen(packet)) {
                    refreshMessages();
                }
                return;
            }
        }
    }

    /** A message was deleted: it disappears from the list (with a short fade) and from the preview. */
    private void applyDelete(ChatProtocol.Packet packet) {
        List<ChatEntry> entries = entriesOf(packet);
        if (entries == null || packet.id == 0) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == packet.id) {
                ChatEntry removed = entries.remove(i);
                sidebar.update(people, activePeer);
                if (isOpen(packet)) {
                    messages.remove(removed);
                }
                return;
            }
        }
    }

    /** "Delete message": asks first, then the server removes it for everybody (a stored file is deleted too). */
    private void deleteMessage(ChatEntry entry) {
        int answer = JOptionPane.showConfirmDialog(frame, "Delete this message for everybody?", "Delete message",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            client.deleteMessage(activePeer, entry.id);
        } catch (IOException error) {
            connectionLost(error);
        }
    }

    // ---------------------------------------------------------------- Composer.Host

    @Override
    public ChatClient client() {
        return client;
    }

    @Override
    public String activePeer() {
        return activePeer;
    }

    @Override
    public Frame frame() {
        return frame;
    }

    @Override
    public void connectionLost(IOException error) {
        if (!client.isClosed()) {
            JOptionPane.showMessageDialog(frame, "Connection lost: " + error.getMessage(),
                    "Connection error", JOptionPane.ERROR_MESSAGE);
            client.close();
        }
        composer.disableAll();
    }

    @Override
    public void notice(String text) {
        receive(new ChatEntry("SYSTEM", now(), text, ChatProtocol.TYPE_SYSTEM, new byte[0]), activePeer);
    }

    /** Shows my message in the AI conversation and sends it to the server, which asks the AI. */
    @Override
    public void askAi(String text) throws IOException {
        receive(new ChatEntry(username, now(), text, ChatProtocol.TYPE_PRIVATE, new byte[0]), ChatProtocol.AI_NAME);
        client.sendAiQuery(ChatProtocol.AI_CHAT, ChatProtocol.AI_NAME, text);
    }

    /** The current time in the format the server uses for messages. */
    private static String now() {
        return "(" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + ")";
    }

    /** Shows a progress or error line above the message box. */
    private void showStatus(String text, boolean error) {
        composer.showStatus(text, error);
    }
}
