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
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
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
    private final WallpaperManager wallpapers = new WallpaperManager(Paths.get("wallpapers"), Settings.getInt("wallpaper", -1));

    // the conversations
    private final List<ChatEntry> groupEntries = new ArrayList<ChatEntry>();
    private final Map<String, List<ChatEntry>> privateEntries = new HashMap<String, List<ChatEntry>>(); // key: lower-case name
    private final Map<String, Boolean> people = new TreeMap<String, Boolean>(String.CASE_INSENSITIVE_ORDER); // name -> online
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
    private Widgets.Button wallpaperButton;
    private Widgets.Button themeButton;

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
        Theme.dark = Settings.getBoolean("darkMode." + username, Settings.getBoolean("darkMode", true));
        this.messages = new MessageView(frame, username);
        this.composer = new Composer(this);
        buildWindow();
        showConversation();
    }

    // ---------------------------------------------------------------- building the window

    private void buildWindow() {
        sidebar = new Sidebar(username, this::openConversation, this);

        Widgets.Glass card = new Widgets.Glass(new BorderLayout(), 56);
        card.add(sidebar, BorderLayout.WEST);
        card.add(chatPanel(), BorderLayout.CENTER);

        JPanel root = new Widgets.WallpaperPanel(wallpapers);
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28)); // the wallpaper around the card
        root.add(card, BorderLayout.CENTER);

        frame.setContentPane(root);
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
        title.setFont(new Font("SansSerif", Font.BOLD, 15));
        subtitle = new Widgets.Label(" ", Theme.Role.MUTED);
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 11));
        JPanel titles = new JPanel(new BorderLayout(0, 2));
        titles.setOpaque(false);
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.SOUTH);
        headerPicture.setOpaque(false);
        JPanel who = new JPanel(new BorderLayout(14, 0));
        who.setOpaque(false);
        who.add(headerPicture, BorderLayout.WEST);
        who.add(titles, BorderLayout.CENTER);

        Widgets.Button addWallpaper = pill("+ Wallpaper");
        addWallpaper.setToolTipText("Choose a picture to add to the wallpapers folder");
        addWallpaper.addActionListener(event -> addWallpaper());
        wallpaperButton = pill("");
        wallpaperButton.setToolTipText("Cycle through the pictures in the wallpapers folder");
        wallpaperButton.addActionListener(event -> cycleWallpaper());
        updateWallpaperButton();
        themeButton = pill("");
        themeButton.setToolTipText("Switch between the dark and the light theme");
        themeButton.addActionListener(event -> toggleTheme());
        updateThemeButton();
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(addWallpaper);
        buttons.add(wallpaperButton);
        buttons.add(themeButton);

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

    private static Widgets.Button pill(String text) {
        Widgets.Button button = new Widgets.Button(text, Theme.Role.CONTROL, Theme.Role.TEXT);
        button.setCorner(30);
        button.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
        return button;
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
        composer.clearSuggestions();
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
            long online = people.values().stream().filter(isOnline -> isOnline).count();
            subtitle.setText(online + " online · " + people.size() + " members");
        } else {
            subtitle.setText(Boolean.TRUE.equals(people.get(activePeer)) ? "Online" : "Offline");
        }
        headerPicture.removeAll();
        headerPicture.add(group ? ProfileAvatar.group(44) : new ProfileAvatar(activePeer, 44).showOnline(people.get(activePeer)));
        headerPicture.revalidate();
        headerPicture.repaint();
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
            messages.addNote("Start a private conversation with " + ProfileNameFormatter.format(activePeer) + ".");
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
        messages.add(entry);
        boolean chatText = entry.type == ChatProtocol.TYPE_TEXT || entry.type == ChatProtocol.TYPE_PRIVATE;
        if (aiAvailable && live && chatText && fromOthers) {
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
                    people.put(user.displayName, user.online);
                }
                refreshPeople();
            } catch (IOException error) {
                composer.showStatus("Could not read the user list: " + error.getMessage(), true);
            }
        } else if (type == ChatProtocol.TYPE_USER_PRESENCE) {
            people.put(packet.metadata, packet.data.length > 0 && packet.data[0] != 0);
            refreshPeople();
        } else if (type == ChatProtocol.TYPE_AI_REPLY) {
            showAiReply(packet.metadata);
        } else if (type == ChatProtocol.TYPE_TRANSFER_STATUS) {
            composer.showStatus(packet.metadata, false);
        } else if (type == ChatProtocol.TYPE_PRIVATE) {
            // metadata holds the recipient; the other person of the chat is the "peer"
            String peer = packet.sender.equalsIgnoreCase(username) ? packet.metadata : packet.sender;
            String text = new String(packet.data, StandardCharsets.UTF_8);
            receive(new ChatEntry(packet.sender, packet.timestamp, text, type, packet.data), peer);
        } else if (type == ChatProtocol.TYPE_PRIVATE_AUDIO) {
            String peer = packet.sender.equalsIgnoreCase(username) ? packet.metadata : packet.sender;
            // shown like a GroupChat voice message (type AUDIO), but kept in the private conversation
            receive(new ChatEntry(packet.sender, packet.timestamp, "voice_message.wav", ChatProtocol.TYPE_AUDIO, packet.data), peer);
        } else if (type == ChatProtocol.TYPE_TEXT || type == ChatProtocol.TYPE_IMAGE || type == ChatProtocol.TYPE_FILE
                || type == ChatProtocol.TYPE_AUDIO || type == ChatProtocol.TYPE_SYSTEM) {
            receive(new ChatEntry(packet.sender, packet.timestamp, packet.metadata, type, packet.data), null);
        }
    }

    /** An answer from the AI assistant: the first character says what it is, the rest is the text. */
    private void showAiReply(String metadata) {
        char kind = metadata.isEmpty() ? ChatProtocol.AI_ASK : metadata.charAt(0);
        String text = metadata.isEmpty() ? "" : metadata.substring(1);
        if (kind == ChatProtocol.AI_SUGGEST) {
            composer.showSuggestions(text.split("\n"));
        } else if (kind == ChatProtocol.AI_COMPLETE) {
            composer.append(text);
        } else {
            notice(text);
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
        String now = "(" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + ")";
        receive(new ChatEntry("SYSTEM", now, text, ChatProtocol.TYPE_SYSTEM, new byte[0]), activePeer);
    }

    // ---------------------------------------------------------------- theme and wallpaper

    private void toggleTheme() {
        Theme.dark = !Theme.dark;
        Settings.putBoolean("darkMode." + username, Theme.dark);
        updateThemeButton();
        refreshPeople();
        refreshMessages(); // the messages are re-created with the new theme's colours
        frame.repaint();
    }

    private void updateThemeButton() {
        themeButton.setText(Theme.dark ? "☀ Light mode" : "☾ Dark mode");
    }

    private void cycleWallpaper() {
        wallpapers.next();
        wallpaperChanged();
    }

    /** "+ Wallpaper": pick a picture; it is copied into the wallpapers folder and shown right away. */
    private void addWallpaper() {
        FileDialog chooser = new FileDialog(frame, "Choose a picture to use as a wallpaper", FileDialog.LOAD);
        chooser.setFilenameFilter((directory, name) -> name.toLowerCase().matches(".*\\.(png|jpe?g|gif|bmp)"));
        chooser.setVisible(true);
        if (chooser.getFile() == null) {
            return;
        }
        try {
            wallpapers.add(new File(chooser.getDirectory(), chooser.getFile()).toPath());
            wallpaperChanged();
            composer.showStatus("Wallpaper added: " + wallpapers.name(), false);
        } catch (IOException error) {
            JOptionPane.showMessageDialog(frame, error.getMessage(), "Could not add wallpaper", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void wallpaperChanged() {
        Settings.putInt("wallpaper", wallpapers.index());
        updateWallpaperButton();
        frame.repaint();
    }

    private void updateWallpaperButton() {
        String name = wallpapers.name();
        wallpaperButton.setText("Wallpaper: " + (name.length() > 14 ? name.substring(0, 13) + "…" : name));
    }
}
