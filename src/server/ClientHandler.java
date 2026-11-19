package server;

import ai.AiContext;
import ai.AiService;
import shared.ChatProtocol;
import shared.Frames;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * One thread per connected client. Its life:
 *   1. signIn()        - check the account name and password (first frame of the connection)
 *   2. joinChat()      - send the saved history, announce the new member
 *   3. readMessages()  - handle what the client sends: text, private messages, files, AI questions
 *   4. cleanup()       - announce that the member left
 */
final class ClientHandler extends Thread {
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    private static final int MAX_VOICE_BYTES = ChatProtocol.MAX_ATTACHMENT_BYTES;

    final Socket socket;
    private DataInputStream input;
    private volatile DataOutputStream output;
    volatile String displayName;
    volatile boolean ready; // true once the client is signed in and has its history
    volatile int status = ChatProtocol.STATUS_ONLINE; // what this person chose to show (online, away, ...)
    private final List<Future<?>> aiTasks = new CopyOnWriteArrayList<Future<?>>();

    ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1024 * 1024));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1024 * 1024));
            if (signIn()) {
                joinChat();
                readMessages();
            }
        } catch (IOException error) {
            if (displayName != null) {
                System.err.println("Connection error for " + displayName + ": " + error.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    // ---------------------------------------------------------------- 1. signing in

    /** Checks the login. Returns false (after telling the client why) when the client may not join. */
    private boolean signIn() throws IOException {
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS); // a client that never logs in must not hold a thread forever
        String requestedName = authenticate();
        if (requestedName == null) {
            return false;
        }
        socket.setSoTimeout(0);
        if (requestedName.length() > 32) {
            refuseLogin("Display names must be 32 characters or fewer.");
            return false;
        }
        synchronized (ChatServer.clients) {
            for (ClientHandler client : ChatServer.clients) {
                if (client != this && client.displayName != null && client.displayName.equalsIgnoreCase(requestedName)) {
                    refuseLogin("That display name is already in use.");
                    return false;
                }
            }
            try {
                ChatServer.rememberUser(requestedName);
            } catch (IOException error) {
                refuseLogin("Could not save your user record: " + error.getMessage());
                return false;
            }
            displayName = requestedName;
        }
        sendPacket(Frames.authOk(displayName, AiService.isAvailable()));
        return true;
    }

    /** Reads the first frame (LOGIN or REGISTER). Returns the account name, or null after reporting the problem. */
    private String authenticate() throws IOException {
        byte type = input.readByte();
        input.readUTF();
        input.readUTF();
        String mode = input.readUTF();
        int length = input.readInt();
        if (type != ChatProtocol.TYPE_AUTH || length < 0 || length > 2048) {
            return refuseLogin("Invalid login request.");
        }
        byte[] body = new byte[length];
        input.readFully(body);
        DataInputStream credentials = new DataInputStream(new ByteArrayInputStream(body));
        String name = credentials.readUTF();
        String password = credentials.readUTF();

        String error = "REGISTER".equals(mode) ? ChatServer.accounts.register(name, password) : null;
        String account = error == null ? ChatServer.accounts.authenticate(name, password) : null;
        if (error == null && account == null) {
            error = "Wrong account name or password.";
        }
        return error == null ? account : refuseLogin(error);
    }

    /** Tells the client why the login failed. Always returns null. */
    private String refuseLogin(String message) {
        sendPacket(ChatProtocol.TYPE_LOGIN_ERROR, "SYSTEM", ChatServer.getTimestamp(), message, new byte[0]);
        return null;
    }

    // ---------------------------------------------------------------- 2. joining

    /** Sends the saved history, then announces the new member to everybody. */
    private void joinChat() {
        // Same lock as new messages, so no message can slip between the history and the live feed.
        synchronized (ChatServer.groupHistory) {
            ChatServer.sendAvatars(this);
            ChatServer.sendGroupHistory(this);
            ChatServer.sendPrivateHistory(this);
            if (socket.isClosed()) {
                return;
            }
            ready = true;
            String joinMessage = "* " + displayName + " joined the chat *";
            System.out.println(ChatServer.getTimestamp() + " " + joinMessage);
            ChatServer.broadcastPacket(ChatProtocol.TYPE_SYSTEM, "SYSTEM", ChatServer.getTimestamp(), joinMessage, new byte[0]);
            ChatServer.broadcastPacket(Frames.presence(displayName, true));
            ChatServer.broadcastUserList();
        }
    }

    // ---------------------------------------------------------------- 3. what the client sends

    private void readMessages() throws IOException {
        while (true) {
            byte type = input.readByte();
            String time = ChatServer.getTimestamp();
            if (type == ChatProtocol.TYPE_PRIVATE) {
                handlePrivateMessage(time);
            } else if (type == ChatProtocol.TYPE_PRIVATE_AUDIO) {
                handlePrivateVoice(time);
            } else if (type == ChatProtocol.TYPE_TEXT) {
                if (!handleTextMessage(time)) {
                    return; // the client said /quit
                }
            } else if (type == ChatProtocol.TYPE_IMAGE || type == ChatProtocol.TYPE_FILE || type == ChatProtocol.TYPE_AUDIO) {
                handleAttachment(type, time);
            } else if (type == ChatProtocol.TYPE_STATUS) {
                int chosen = input.readByte();
                if (chosen >= ChatProtocol.STATUS_ONLINE && chosen <= ChatProtocol.STATUS_INVISIBLE) {
                    status = chosen;
                    ChatServer.broadcastUserList(); // everybody sees the new status
                }
            } else if (type == ChatProtocol.TYPE_AVATAR) {
                handleAvatar(time);
            } else if (type == ChatProtocol.TYPE_AI_QUERY) {
                handleAiQuery();
            }
        }
    }

    private boolean handleTextMessage(String time) throws IOException {
        String text = input.readUTF();
        if (text.equalsIgnoreCase("/quit")) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.matches("(?is)^(/ai|@ai)(\\s+.*)?$")) { // "/ai question" asks the assistant instead of posting
            submitAi(ChatProtocol.AI_ASK, "", trimmed.replaceFirst("(?is)^(/ai|@ai)\\s*", ""));
            return true;
        }
        text = ProfanityFilter.filter(text);
        synchronized (ChatServer.groupHistory) {
            try {
                ChatServer.persistGroupMessage(ChatProtocol.TYPE_TEXT, displayName, time, text, new byte[0]);
            } catch (IOException error) {
                sendPacket(ChatProtocol.TYPE_TRANSFER_STATUS, "SYSTEM", time, "Message was not sent: " + error.getMessage(), new byte[0]);
                return true;
            }
            System.out.println(time + " " + displayName + " : " + text);
            ChatServer.broadcastPacket(ChatProtocol.TYPE_TEXT, displayName, time, text, new byte[0]);
        }
        return true;
    }

    private void handlePrivateMessage(String time) throws IOException {
        String recipientName = input.readUTF();
        String text = input.readUTF();
        if (recipientName.equalsIgnoreCase(displayName) || text.length() > 60000) {
            sendPacket(ChatProtocol.TYPE_TRANSFER_STATUS, "SYSTEM", time, "Private message could not be sent.", new byte[0]);
            return;
        }
        String recipientAccount = ChatServer.canonicalUser(recipientName);
        if (recipientAccount == null) {
            sendPacket(ChatProtocol.TYPE_TRANSFER_STATUS, "SYSTEM", time, "Unknown user: " + recipientName, new byte[0]);
            return;
        }
        synchronized (ChatServer.groupHistory) {
            try {
                ChatServer.privateHistory.add(displayName, recipientAccount, time, text);
            } catch (IOException error) {
                sendPacket(ChatProtocol.TYPE_TRANSFER_STATUS, "SYSTEM", time, "Message was not sent: " + error.getMessage(), new byte[0]);
                return;
            }
            byte[] message = text.getBytes(StandardCharsets.UTF_8);
            sendPacket(ChatProtocol.TYPE_PRIVATE, displayName, time, recipientAccount, message); // the sender sees it too
            ClientHandler recipient = ChatServer.findOnlineUser(recipientAccount);
            if (recipient != null) {
                recipient.sendPacket(ChatProtocol.TYPE_PRIVATE, displayName, time, recipientAccount, message);
            }
        }
    }

    /** A voice message for one person: saved, then sent to the sender (so it shows up there too) and the recipient. */
    private void handlePrivateVoice(String time) throws IOException {
        String recipientName = input.readUTF();
        input.readUTF(); // the file name is not needed
        int length = input.readInt();
        if (length < 1 || length > MAX_VOICE_BYTES) {
            System.err.println("Rejected a private voice message of " + length + " bytes from " + displayName);
            socket.close();
            return;
        }
        byte[] wav = new byte[length];
        input.readFully(wav);
        String recipientAccount = recipientName.equalsIgnoreCase(displayName) ? null : ChatServer.canonicalUser(recipientName);
        if (recipientAccount == null) {
            sendPacket(ChatProtocol.TYPE_TRANSFER_STATUS, "SYSTEM", time, "Voice message could not be sent.", new byte[0]);
            return;
        }
        synchronized (ChatServer.groupHistory) {
            try {
                ChatServer.privateHistory.addVoice(displayName, recipientAccount, time, wav);
            } catch (IOException error) {
                sendPacket(ChatProtocol.TYPE_TRANSFER_STATUS, "SYSTEM", time, "Voice message was not sent: " + error.getMessage(), new byte[0]);
                return;
            }
            sendPacket(ChatProtocol.TYPE_PRIVATE_AUDIO, displayName, time, recipientAccount, wav);
            ClientHandler recipient = ChatServer.findOnlineUser(recipientAccount);
            if (recipient != null) {
                recipient.sendPacket(ChatProtocol.TYPE_PRIVATE_AUDIO, displayName, time, recipientAccount, wav);
            }
        }
    }

    /** A new profile picture: saved, then sent to everybody (the sender included, so it shows up everywhere). */
    private void handleAvatar(String time) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > ChatProtocol.MAX_AVATAR_BYTES) {
            System.err.println("Rejected a profile picture of " + length + " bytes from " + displayName);
            socket.close();
            return;
        }
        byte[] png = new byte[length];
        input.readFully(png);
        ChatServer.saveAvatar(displayName, png);
        ChatServer.broadcastPacket(ChatProtocol.TYPE_AVATAR, displayName, time, "", png);
    }

    /** An image, file or voice message for GroupChat. */
    private void handleAttachment(byte type, String time) throws IOException {
        String fileName = input.readUTF();
        int length = input.readInt();
        if (length < 0 || length > ChatProtocol.MAX_ATTACHMENT_BYTES) {
            System.err.println("Rejected oversized payload from " + displayName);
            socket.close();
            return;
        }
        byte[] fileBytes = new byte[length];
        input.readFully(fileBytes);
        synchronized (ChatServer.groupHistory) {
            try {
                ChatServer.persistGroupMessage(type, displayName, time, fileName, fileBytes);
            } catch (IOException error) {
                sendPacket(ChatProtocol.TYPE_TRANSFER_STATUS, "SYSTEM", time,
                        "Transfer rejected; message was not saved: " + error.getMessage(), new byte[0]);
                return;
            }
            String label = type == ChatProtocol.TYPE_IMAGE ? "[Image: " + fileName + "]"
                    : type == ChatProtocol.TYPE_AUDIO ? "[Audio Message (" + length / 1024 + " KB)]"
                    : "[File: " + fileName + " (" + length / 1024 + " KB)]";
            System.out.println(time + " " + displayName + " sent " + label);
            ChatServer.broadcastPacket(type, displayName, time, fileName, fileBytes);
            sendPacket(ChatProtocol.TYPE_TRANSFER_STATUS, "SYSTEM", ChatServer.getTimestamp(), "Transfer accepted: " + fileName, new byte[0]);
        }
    }

    // ---------------------------------------------------------------- AI assistant

    /** AI request from the client: sender = conversation ("" = GroupChat), metadata = kind character + text. */
    private void handleAiQuery() throws IOException {
        String conversation = input.readUTF();
        input.readUTF();
        String query = input.readUTF();
        if (!query.isEmpty() && query.length() <= 60000) {
            submitAi(query.charAt(0), conversation, query.substring(1));
        }
    }

    /** Runs the request in the background (see the ai package) and sends the answer to this client only. */
    private void submitAi(char kind, String conversation, String text) {
        AiService.Kind aiKind = kind == ChatProtocol.AI_SUGGEST ? AiService.Kind.SUGGEST
                : kind == ChatProtocol.AI_COMPLETE ? AiService.Kind.COMPLETE
                : kind == ChatProtocol.AI_ASK ? AiService.Kind.ASK : null;
        if (aiKind == null || (aiKind != AiService.Kind.ASK && !AiService.isAvailable())) {
            return;
        }
        AiContext context = new AiContext(displayName, conversation, text, ChatServer.recentMessages(displayName, conversation, 20));
        try {
            aiTasks.removeIf(Future::isDone);
            aiTasks.add(AiService.request(aiKind, context,
                    result -> sendPacket(Frames.aiReply(kind, result, ChatServer.getTimestamp())), () -> { }));
        } catch (RejectedExecutionException busy) {
            if (aiKind == AiService.Kind.ASK) {
                sendPacket(ChatProtocol.TYPE_SYSTEM, "SYSTEM", ChatServer.getTimestamp(), "AI is busy; please try again shortly.", new byte[0]);
            }
        }
    }

    // ---------------------------------------------------------------- sending to this client

    void sendPacket(ChatProtocol.Packet packet) {
        sendPacket(packet.type, packet.sender, packet.timestamp, packet.metadata, packet.data);
    }

    /** Every packet has the same shape: type, sender, timestamp, metadata, and (for some types) bytes. */
    synchronized void sendPacket(byte type, String sender, String timestamp, String metadata, byte[] data) {
        if (output == null) {
            return;
        }
        try {
            output.writeByte(type);
            output.writeUTF(sender);
            output.writeUTF(timestamp);
            output.writeUTF(metadata);
            if (ChatProtocol.carriesBytes(type)) {
                output.writeInt(data.length);
                output.write(data);
            }
            output.flush();
        } catch (IOException error) {
            System.err.println("Could not deliver a chat packet to "
                    + (displayName == null ? "a connecting client" : displayName) + ": " + error.getMessage());
            try {
                socket.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
        }
    }

    // ---------------------------------------------------------------- 4. leaving

    private void cleanup() {
        boolean wasReady = ready;
        ready = false;
        ChatServer.removeClient(this);
        if (wasReady && displayName != null) {
            String leaveMessage = "* " + displayName + " left the chat *";
            System.out.println(ChatServer.getTimestamp() + " " + leaveMessage);
            ChatServer.broadcastPacket(ChatProtocol.TYPE_SYSTEM, "SYSTEM", ChatServer.getTimestamp(), leaveMessage, new byte[0]);
            ChatServer.broadcastPacket(Frames.presence(displayName, false));
            ChatServer.broadcastUserList();
        }
        for (Future<?> pendingAi : aiTasks) {
            pendingAi.cancel(true);
        }
        try {
            socket.close();
        } catch (IOException error) {
            System.err.println("Could not close connection for " + displayName + ": " + error.getMessage());
        }
    }
}
