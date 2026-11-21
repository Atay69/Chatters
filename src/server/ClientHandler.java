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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final ExecutorService DOWNLOADS = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "file-download");
        thread.setDaemon(true);
        return thread;
    });

    final Socket socket;
    private DataInputStream input;
    private volatile DataOutputStream output;
    volatile String displayName;
    volatile boolean ready; // true once the client is signed in and has its history
    volatile int status = ChatProtocol.STATUS_ONLINE; // what this person chose to show (online, away, ...)
    private final List<Future<?>> aiTasks = new CopyOnWriteArrayList<Future<?>>();
    private final List<String> aiChat = Collections.synchronizedList(new ArrayList<String>()); // this client's talk with the AI ("name: text")

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
            } else if (type == ChatProtocol.TYPE_TEXT) {
                if (!handleTextMessage(time)) {
                    return; // the client said /quit
                }
            } else if (type == ChatProtocol.TYPE_MSG_EDIT) {
                handleMessageEdit();
            } else if (type == ChatProtocol.TYPE_MSG_DELETE) {
                handleMessageDelete();
            } else if (type == ChatProtocol.TYPE_FILE_BEGIN) {
                handleFileBegin();
            } else if (type == ChatProtocol.TYPE_FILE_CHUNK) {
                handleFileChunk();
            } else if (type == ChatProtocol.TYPE_FILE_END) {
                handleFileEnd(time);
            } else if (type == ChatProtocol.TYPE_FILE_GET) {
                handleFileGet();
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
        postToGroup(time, ProfanityFilter.filter(text));
        return true;
    }

    /** Saves a message (text, or a stored file) in GroupChat and sends it to everybody. */
    private void postToGroup(String time, String text) {
        synchronized (ChatServer.groupHistory) {
            long id;
            try {
            id = ChatServer.persistGroupMessage(ChatProtocol.TYPE_TEXT, displayName, time, text, new byte[0]);
            } catch (IOException error) {
                sendStatus("Message was not sent: " + error.getMessage());
                return;
            }
            System.out.println(time + " " + displayName + " : " + (text.startsWith(ChatProtocol.FILE_MARK) ? "[file]" : text));
            ChatServer.broadcastPacket(ChatProtocol.TYPE_TEXT, displayName, time, text, new byte[0], id);
        }
    }

    private void handlePrivateMessage(String time) throws IOException {
        String recipientName = input.readUTF();
        String text = input.readUTF();
        if (recipientName.equalsIgnoreCase(displayName) || text.length() > 60000) {
            sendStatus("Private message could not be sent.");
            return;
        }
        String recipientAccount = ChatServer.canonicalUser(recipientName);
        if (recipientAccount == null) {
            sendStatus("Unknown user: " + recipientName);
            return;
        }
        postPrivate(time, recipientAccount, text);
    }

    /** Saves a private message (text, or a stored file) and sends it to the sender and the recipient. */
    private void postPrivate(String time, String recipientAccount, String text) {
        synchronized (ChatServer.groupHistory) {
            PrivateHistory.Message saved;
            try {
                saved = ChatServer.privateHistory.add(displayName, recipientAccount, time, text);
            } catch (IOException error) {
                sendStatus("Message was not sent: " + error.getMessage());
                return;
            }
            byte[] message = text.getBytes(StandardCharsets.UTF_8);
            sendToBoth(recipientAccount, ChatProtocol.TYPE_PRIVATE, time, message, saved.id);
        }
    }

    // ---------------------------------------------------------------- editing and deleting messages

    /** Sends one packet to me and to the other person of a private chat (if online). */
    private void sendToBoth(String recipientAccount, byte type, String time, byte[] data, long id) {
        sendPacket(type, displayName, time, recipientAccount, data, id);
        ClientHandler recipient = ChatServer.findOnlineUser(recipientAccount);
        if (recipient != null) {
            recipient.sendPacket(type, displayName, time, recipientAccount, data, id);
        }
    }

    /** "Edit": change the text of one of MY text messages. {@code conversation} is "" for GroupChat, else the other person. */
    private void handleMessageEdit() throws IOException {
        String conversation = input.readUTF();
        long id = input.readLong();
        String text = input.readUTF();
        if (text.trim().isEmpty() || text.length() > 60000) {
            sendStatus("Edit refused: the message is empty or too long.");
            return;
        }
        String time = ChatServer.getTimestamp();
        synchronized (ChatServer.groupHistory) {
            try {
                if (conversation.isEmpty()) {
                    ChatServer.StoredMessage message = ChatServer.findGroup(id);
                    if (message == null || !message.sender.equalsIgnoreCase(displayName)
                            || message.type != ChatProtocol.TYPE_TEXT || message.meta.startsWith(ChatProtocol.FILE_MARK)) {
                        sendStatus("You can only edit your own text messages.");
                        return;
                    }
                    String filtered = ProfanityFilter.filter(text);
                    ChatServer.editGroup(message, filtered);
                    ChatServer.broadcastPacket(ChatProtocol.TYPE_MSG_EDITED, displayName, time, "",
                            filtered.getBytes(StandardCharsets.UTF_8), id);
                } else {
                    String recipient = ChatServer.canonicalUser(conversation);
                    PrivateHistory.Message message = ChatServer.privateHistory.find(id);
                    if (recipient == null || message == null || !message.sender.equalsIgnoreCase(displayName)
                            || !message.recipient.equalsIgnoreCase(recipient) || message.audio != null
                            || message.text.startsWith(ChatProtocol.FILE_MARK)) {
                        sendStatus("You can only edit your own text messages.");
                        return;
                    }
                    ChatServer.privateHistory.edit(message, text);
                    sendToBoth(recipient, ChatProtocol.TYPE_MSG_EDITED, time, text.getBytes(StandardCharsets.UTF_8), id);
                }
            } catch (IOException error) {
                sendStatus("Edit failed: " + error.getMessage());
            }
        }
    }

    /** "Delete": remove one of MY messages for everybody (a stored file is deleted from the server's disk too). */
    private void handleMessageDelete() throws IOException {
        String conversation = input.readUTF();
        long id = input.readLong();
        String time = ChatServer.getTimestamp();
        synchronized (ChatServer.groupHistory) {
            try {
                if (conversation.isEmpty()) {
                    ChatServer.StoredMessage message = ChatServer.findGroup(id);
                    if (message == null || !message.sender.equalsIgnoreCase(displayName)) {
                        sendStatus("You can only delete your own messages.");
                        return;
                    }
                    ChatServer.deleteGroup(message);
                    FileStore.deleteOf(message.meta);
                    ChatServer.broadcastPacket(ChatProtocol.TYPE_MSG_DELETED, displayName, time, "", new byte[0], id);
                } else {
                    String recipient = ChatServer.canonicalUser(conversation);
                    PrivateHistory.Message message = ChatServer.privateHistory.find(id);
                    if (recipient == null || message == null || !message.sender.equalsIgnoreCase(displayName)
                            || !message.recipient.equalsIgnoreCase(recipient)) {
                        sendStatus("You can only delete your own messages.");
                        return;
                    }
                    ChatServer.privateHistory.delete(message);
                    FileStore.deleteOf(message.text);
                    sendToBoth(recipient, ChatProtocol.TYPE_MSG_DELETED, time, new byte[0], id);
                }
            } catch (IOException error) {
                sendStatus("Delete failed: " + error.getMessage());
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

    // ---------------------------------------------------------------- files (sent in chunks, stored on disk)

    // The upload that is arriving right now (a client uploads one file at a time).
    private OutputStream uploadOut;          // null = no upload running
    private long uploadId;
    private long uploadSize;
    private long uploadReceived;
    private byte uploadKind;
    private String uploadName;
    private String uploadRecipient;          // "" = GroupChat
    private byte[] chunkBuffer = new byte[ChatProtocol.FILE_CHUNK_BYTES];

    /** The client announces a file: who gets it, its name, what kind it is and how big it is. */
    private void handleFileBegin() throws IOException {
        String recipient = input.readUTF();
        String name = FileStore.cleanName(input.readUTF());
        byte kind = input.readByte();
        long size = input.readLong();
        abortUpload(); // a new upload replaces one that was never finished

        String problem = null;
        if (kind != ChatProtocol.TYPE_IMAGE && kind != ChatProtocol.TYPE_FILE && kind != ChatProtocol.TYPE_AUDIO) {
            problem = "unknown kind of file";
        } else if (size < 0) {
            problem = "invalid size";
        } else if (!recipient.isEmpty() && (recipient.equalsIgnoreCase(displayName) || ChatServer.canonicalUser(recipient) == null)) {
            problem = "unknown recipient";
        }
        if (problem == null) {
            try {
                uploadId = FileStore.newId();
                uploadOut = new BufferedOutputStream(Files.newOutputStream(FileStore.partPath(uploadId)), 1024 * 1024);
            } catch (IOException error) {
                problem = "the server could not save the file (" + error.getMessage() + ")";
            }
        }
        if (problem != null) {
            sendStatus("Upload refused: " + problem); // its chunks will arrive anyway and are thrown away
            return;
        }
        uploadSize = size;
        uploadReceived = 0;
        uploadKind = kind;
        uploadName = name;
        uploadRecipient = recipient;
    }

    /** One piece of the file: written straight to disk, never kept in memory. */
    private void handleFileChunk() throws IOException {
        int length = input.readInt();
        if (length < 1 || length > ChatProtocol.MAX_CHUNK_BYTES) {
            throw new IOException("invalid chunk length " + length);
        }
        if (chunkBuffer.length < length) {
            chunkBuffer = new byte[length];
        }
        input.readFully(chunkBuffer, 0, length);
        if (uploadOut == null) {
            return; // refused or cancelled upload: just throw the piece away
        }
        uploadReceived += length;
        if (uploadReceived > uploadSize) {
            failUpload("more data than announced");
            return;
        }
        try {
            uploadOut.write(chunkBuffer, 0, length);
        } catch (IOException error) {
            failUpload("the server could not save the file (" + error.getMessage() + ")");
        }
    }

    /** The upload is finished (1) or was cancelled by the sender (0). */
    private void handleFileEnd(String time) throws IOException {
        byte finished = input.readByte();
        if (uploadOut == null) {
            return;
        }
        if (finished != 1 || uploadReceived != uploadSize) {
            abortUpload(); // cancelled, or not all bytes arrived: nothing is published
            return;
        }
        try {
            uploadOut.close();
            uploadOut = null;
            Files.move(FileStore.partPath(uploadId), FileStore.path(uploadId));
        } catch (IOException error) {
            failUpload("the server could not save the file (" + error.getMessage() + ")");
            return;
        }
        // the file is complete: tell the chat about it (a message that points to the stored file)
        String text = ChatProtocol.fileMessage(uploadId, uploadKind, uploadSize, uploadName);
        System.out.println(time + " " + displayName + " uploaded " + uploadName + " (" + uploadSize + " bytes)");
        if (uploadRecipient.isEmpty()) {
            postToGroup(time, text);
        } else {
            postPrivate(time, ChatServer.canonicalUser(uploadRecipient), text);
        }
        sendStatus("Transfer accepted: " + uploadName);
    }

    /** Stops the running upload and deletes what was received of it. */
    private void abortUpload() {
        if (uploadOut == null) {
            return;
        }
        try {
            uploadOut.close();
            Files.deleteIfExists(FileStore.partPath(uploadId));
        } catch (IOException ignored) {
            // nothing more we can do
        }
        uploadOut = null;
    }

    private void failUpload(String reason) {
        abortUpload();
        sendStatus("Upload failed: " + reason);
    }

    private void sendStatus(String text) {
        sendPacket(ChatProtocol.TYPE_TRANSFER_STATUS, "SYSTEM", ChatServer.getTimestamp(), text, new byte[0]);
    }

    /** The client asks for a stored file; it is sent in chunks by a separate thread so chat keeps flowing. */
    private void handleFileGet() throws IOException {
        long id = input.readLong();
        long request = input.readLong();
        DOWNLOADS.execute(() -> sendStoredFile(id, request));
    }

    private void sendStoredFile(long id, long request) {
        boolean complete = false;
        try (InputStream file = Files.newInputStream(FileStore.path(id))) {
            byte[] buffer = new byte[ChatProtocol.FILE_CHUNK_BYTES];
            int count;
            while ((count = file.read(buffer)) > 0) {
                sendFileData(request, buffer, count);
            }
            complete = true;
        } catch (IOException error) {
            // missing file or lost connection: the client is told below (if it is still there)
        }
        sendFileDone(request, complete);
    }

    /** Both methods take the same lock as sendPacket, so a chunk is never mixed into another packet. */
    private synchronized void sendFileData(long request, byte[] data, int length) throws IOException {
        output.writeByte(ChatProtocol.TYPE_FILE_DATA);
        output.writeLong(request);
        output.writeInt(length);
        output.write(data, 0, length);
        output.flush();
    }

    private synchronized void sendFileDone(long request, boolean complete) {
        try {
            output.writeByte(ChatProtocol.TYPE_FILE_DONE);
            output.writeLong(request);
            output.writeByte(complete ? 1 : 0);
            output.flush();
        } catch (IOException error) {
            // the client is gone
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

    /**
     * Runs the request in the background (see the ai package) and sends the answer to this client only.
     * Kinds: SUGGEST / COMPLETE / ASK as before, and CHAT for the "AI Assistant" conversation, whose
     * memory is {@link #aiChat} (this client's talk with the AI) instead of a stored chat.
     */
    private void submitAi(char kind, String conversation, String text) {
        AiService.Kind aiKind = kind == ChatProtocol.AI_SUGGEST ? AiService.Kind.SUGGEST
                : kind == ChatProtocol.AI_COMPLETE ? AiService.Kind.COMPLETE : AiService.Kind.ASK;
        if (aiKind != AiService.Kind.ASK && !AiService.isAvailable()) {
            return;
        }
        boolean chat = kind == ChatProtocol.AI_CHAT;
        List<String> recent = chat ? new ArrayList<String>(aiChat) : ChatServer.recentMessages(displayName, conversation, 20);
        AiContext context = new AiContext(displayName, conversation, text, recent.subList(Math.max(0, recent.size() - 20), recent.size()));
        try {
            aiTasks.removeIf(Future::isDone);
            aiTasks.add(AiService.request(aiKind, context, result -> {
                if (chat) { // remember both sides for the next question
                    aiChat.add(displayName + ": " + text);
                    aiChat.add("AI: " + result);
                }
                sendPacket(Frames.aiReply(kind, result, ChatServer.getTimestamp()));
            }, () -> { }));
        } catch (RejectedExecutionException busy) {
            if (aiKind == AiService.Kind.ASK) {
                sendPacket(ChatProtocol.TYPE_SYSTEM, "SYSTEM", ChatServer.getTimestamp(), "AI is busy; please try again shortly.", new byte[0]);
            }
        }
    }

    // ---------------------------------------------------------------- sending to this client

    void sendPacket(ChatProtocol.Packet packet) {
        sendPacket(packet.type, packet.sender, packet.timestamp, packet.metadata, packet.data, packet.id);
    }

    void sendPacket(byte type, String sender, String timestamp, String metadata, byte[] data) {
        sendPacket(type, sender, timestamp, metadata, data, 0);
    }

    /** Every packet has the same shape: type, sender, timestamp, metadata, the id (chat messages only) and (for some types) bytes. */
    synchronized void sendPacket(byte type, String sender, String timestamp, String metadata, byte[] data, long id) {
        if (output == null) {
            return;
        }
        try {
            output.writeByte(type);
            output.writeUTF(sender);
            output.writeUTF(timestamp);
            output.writeUTF(metadata);
            if (ChatProtocol.hasMessageId(type)) {
                output.writeLong(id);
            }
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
        abortUpload(); // a half-received file must not stay on disk
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
