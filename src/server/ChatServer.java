package server;

import shared.AppInfo;
import shared.ChatProtocol;
import shared.Frames;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The server: accepts connections (one ClientHandler thread per client) and keeps what everybody
 * shares. Everything is saved in the "chatter-data" folder, so it survives a restart:
 *
 *   accounts.bin         account names + password hashes   (UserStore)
 *   users.bin            every name that ever signed in (shown in the members list)
 *   group-history.bin    GroupChat messages, images, files and voice messages
 *   private-messages.bin private messages and voice messages (PrivateHistory; the older
 *                        text-only private-history.bin is still read)
 */
public class ChatServer {
    private static final int PORT = Integer.getInteger("chat.port", 5000);
    private static final long MAX_HISTORY_BYTES = Long.MAX_VALUE; // no limit on the saved GroupChat history
    private static final Path DATA_DIRECTORY = Paths.get(System.getProperty("chat.dataDir", "chatter-data"));
    private static final Path GROUP_HISTORY_FILE = DATA_DIRECTORY.resolve("group-history.bin");
    private static final Path USERS_FILE = DATA_DIRECTORY.resolve("users.bin");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Every connected client (also those that are still logging in). */
    static final Set<ClientHandler> clients = Collections.synchronizedSet(new HashSet<ClientHandler>());
    private static final Set<String> knownUsers = Collections.synchronizedSet(new TreeSet<String>(String.CASE_INSENSITIVE_ORDER));
    /** The saved GroupChat messages. Also the lock that keeps "history, then live messages" in order. */
    static final List<StoredMessage> groupHistory = new ArrayList<StoredMessage>();
    static UserStore accounts;
    static PrivateHistory privateHistory;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            loadSavedData();
            System.out.println("==========================================");
            System.out.println(" " + AppInfo.NAME + " Group Chat Server Started!");
            System.out.println(" Local IP Address : " + getLocalIpAddress() + " (Share this with people on your WiFi)");
            System.out.println(" Listening on Port: " + PORT);
            System.out.println("==========================================");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println(getTimestamp() + " New connection from: " + clientSocket.getInetAddress().getHostAddress());
                ClientHandler handler = new ClientHandler(clientSocket);
                clients.add(handler);
                handler.start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- saved data

    private static void loadSavedData() throws IOException {
        Files.createDirectories(DATA_DIRECTORY);
        accounts = new UserStore(DATA_DIRECTORY.resolve("accounts.bin"));
        privateHistory = new PrivateHistory(DATA_DIRECTORY.resolve("private-history.bin"),
                DATA_DIRECTORY.resolve("private-messages.bin"));
        loadKnownUsers();
        knownUsers.addAll(accounts.names()); // every registered account is always listed
        loadGroupHistory();
    }

    private static void loadKnownUsers() throws IOException {
        if (!Files.exists(USERS_FILE)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(USERS_FILE.toFile())))) {
            while (in.available() > 0) {
                knownUsers.add(in.readUTF());
            }
        } catch (IOException damaged) {
            // Not a valid user list (for example edited by hand): keep a copy aside and start a fresh one.
            Path aside = USERS_FILE.resolveSibling("users.bin.damaged");
            Files.move(USERS_FILE, aside, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("users.bin was damaged (" + damaged + "); moved to " + aside + " and started a new list.");
        }
    }

    private static void loadGroupHistory() throws IOException {
        if (!Files.exists(GROUP_HISTORY_FILE)) {
            return;
        }
        if (Files.size(GROUP_HISTORY_FILE) > MAX_HISTORY_BYTES) {
            throw new IOException("Group history is too large.");
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(GROUP_HISTORY_FILE.toFile())))) {
            while (in.available() > 0) {
                byte type = in.readByte();
                String sender = in.readUTF();
                String timestamp = in.readUTF();
                String meta = in.readUTF();
                int length = in.readInt();
                boolean validType = type == ChatProtocol.TYPE_TEXT || type == ChatProtocol.TYPE_IMAGE
                        || type == ChatProtocol.TYPE_FILE || type == ChatProtocol.TYPE_AUDIO;
                if (length < 0 || length > ChatProtocol.MAX_ATTACHMENT_BYTES || !validType) {
                    throw new IOException("Invalid group-history record.");
                }
                byte[] data = new byte[length];
                in.readFully(data);
                groupHistory.add(new StoredMessage(type, sender, timestamp, meta, data));
            }
        }
    }

    /** Remembers a name that signed in, for the members list. */
    static void rememberUser(String displayName) throws IOException {
        synchronized (knownUsers) {
            if (!knownUsers.add(displayName)) {
                return;
            }
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(USERS_FILE.toFile(), true)))) {
                out.writeUTF(displayName);
            } catch (IOException error) {
                knownUsers.remove(displayName);
                throw error;
            }
        }
    }

    /** Saves a GroupChat message (text, image, file or voice message) on disk and in memory. */
    static void persistGroupMessage(byte type, String sender, String timestamp, String meta, byte[] data) throws IOException {
        synchronized (groupHistory) {
            long recordSize = 1L + 2 + sender.length() * 3L + 2 + timestamp.length() * 3L + 2 + meta.length() * 3L + 4 + data.length;
            if (Files.exists(GROUP_HISTORY_FILE) && Files.size(GROUP_HISTORY_FILE) + recordSize > MAX_HISTORY_BYTES) {
                throw new IOException("Persistent group history is full.");
            }
            Files.createDirectories(DATA_DIRECTORY);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(GROUP_HISTORY_FILE.toFile(), true)))) {
                out.writeByte(type);
                out.writeUTF(sender);
                out.writeUTF(timestamp);
                out.writeUTF(meta);
                out.writeInt(data.length);
                out.write(data);
            }
            groupHistory.add(new StoredMessage(type, sender, timestamp, meta, data));
        }
    }

    static void sendGroupHistory(ClientHandler target) {
        synchronized (groupHistory) {
            for (StoredMessage message : groupHistory) {
                target.sendPacket(message.type, message.sender, message.timestamp, message.meta, message.data);
            }
        }
    }

    /** Sends every saved private message the user sent or received (same frame as a live private message). */
    static void sendPrivateHistory(ClientHandler target) {
        for (PrivateHistory.Message message : privateHistory.forUser(target.displayName)) {
            if (message.audio == null) {
                target.sendPacket(ChatProtocol.TYPE_PRIVATE, message.sender, message.timestamp, message.recipient,
                        message.text.getBytes(StandardCharsets.UTF_8));
            } else {
                target.sendPacket(ChatProtocol.TYPE_PRIVATE_AUDIO, message.sender, message.timestamp, message.recipient, message.audio);
            }
        }
    }

    /** One saved GroupChat message. */
    static class StoredMessage {
        private final byte type;
        private final String sender;
        private final String timestamp;
        private final String meta;   // the text, or the file name
        private final byte[] data;   // the file / image / voice bytes (empty for text)

        private StoredMessage(byte type, String sender, String timestamp, String meta, byte[] data) {
            this.type = type;
            this.sender = sender;
            this.timestamp = timestamp;
            this.meta = meta;
            this.data = data;
        }
    }

    // ---------------------------------------------------------------- profile pictures

    /** Every user's picture is one small PNG: chatter-data/avatars/<name>.png */
    private static final Path AVATAR_DIRECTORY = DATA_DIRECTORY.resolve("avatars");

    private static Path avatarFile(String user) throws IOException {
        // URL-encoding makes the name safe as a file name and keeps different names different
        return AVATAR_DIRECTORY.resolve(java.net.URLEncoder.encode(user.toLowerCase(), "UTF-8") + ".png");
    }

    /** Saves someone's new picture. */
    static void saveAvatar(String user, byte[] png) throws IOException {
        Files.createDirectories(AVATAR_DIRECTORY);
        Files.write(avatarFile(user), png);
    }

    /** Sends the picture of everybody who has one to a client that just signed in. */
    static void sendAvatars(ClientHandler target) {
        synchronized (knownUsers) {
            for (String user : knownUsers) {
                try {
                    Path file = avatarFile(user);
                    if (Files.exists(file)) {
                        target.sendPacket(ChatProtocol.TYPE_AVATAR, user, getTimestamp(), "", Files.readAllBytes(file));
                    }
                } catch (IOException unreadable) {
                    System.err.println("Could not read the picture of " + user + ": " + unreadable.getMessage());
                }
            }
        }
    }

    // ---------------------------------------------------------------- who is who

    /** The registered spelling of a known user's name, or null when nobody by that name exists. */
    static String canonicalUser(String name) {
        synchronized (knownUsers) {
            for (String knownUser : knownUsers) {
                if (knownUser.equalsIgnoreCase(name)) {
                    return knownUser;
                }
            }
        }
        return null;
    }

    static ClientHandler findOnlineUser(String name) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client.ready && client.displayName.equalsIgnoreCase(name) && !client.socket.isClosed()) {
                    return client;
                }
            }
        }
        return null;
    }

    /** Latest messages of a conversation as "sender: text" lines for the AI ("" = GroupChat, else the partner). */
    static List<String> recentMessages(String user, String conversation, int limit) {
        List<String> lines = new ArrayList<String>();
        if (conversation.isEmpty()) {
            synchronized (groupHistory) {
                for (StoredMessage message : groupHistory) {
                    if (message.type == ChatProtocol.TYPE_TEXT) {
                        lines.add(message.sender + ": " + message.meta);
                    }
                }
            }
        } else {
            for (PrivateHistory.Message message : privateHistory.between(user, conversation, limit)) {
                lines.add(message.sender + ": " + message.text);
            }
        }
        return new ArrayList<String>(lines.subList(Math.max(0, lines.size() - limit), lines.size()));
    }

    // ---------------------------------------------------------------- sending to everybody

    static void removeClient(ClientHandler client) {
        clients.remove(client);
    }

    /** Sends a packet to every client that has finished logging in. */
    static void broadcastPacket(byte type, String sender, String timestamp, String meta, byte[] data) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client.ready) {
                    client.sendPacket(type, sender, timestamp, meta, data);
                }
            }
        }
    }

    static void broadcastPacket(ChatProtocol.Packet packet) {
        broadcastPacket(packet.type, packet.sender, packet.timestamp, packet.metadata, packet.data);
    }

    /** Tells every client who exists and who is online (after somebody joins or leaves). */
    static void broadcastUserList() {
        synchronized (clients) {
            List<ChatProtocol.UserRecord> users = new ArrayList<ChatProtocol.UserRecord>();
            synchronized (knownUsers) {
                for (String knownUser : knownUsers) {
                    ClientHandler client = findOnlineUser(knownUser);
                    // somebody who chose "invisible" looks offline to everybody else
                    int status = client == null || client.status == ChatProtocol.STATUS_INVISIBLE ? ChatProtocol.STATUS_OFFLINE : client.status;
                    users.add(new ChatProtocol.UserRecord(knownUser, status));
                }
            }
            ChatProtocol.Packet list = Frames.userList(users);
            for (ClientHandler client : clients) {
                if (client.ready) {
                    client.sendPacket(list);
                }
            }
        }
    }

    // ---------------------------------------------------------------- small helpers

    /** The time as the clients expect it, e.g. "(2026-11-18T17:40:12)". */
    static String getTimestamp() {
        return "(" + LocalDateTime.now().format(TIME_FORMAT) + ")";
    }

    private static String getLocalIpAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002); // no data is sent; this only picks the network card
            return socket.getLocalAddress().getHostAddress();
        } catch (Exception noInternet) {
            return "127.0.0.1";
        }
    }
}
