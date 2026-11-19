package client;

import shared.ChatProtocol;
import shared.Frames;

import javax.swing.SwingUtilities;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatClient implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final String username;
    private final boolean aiAvailable;
    private volatile Listener listener;
    private volatile boolean closed;

    public interface Listener {
        void onPacket(ChatProtocol.Packet packet);
        void onDisconnected(IOException error);
    }

    public interface TransferListener {
        void onProgress(long sent, long total);
        void onComplete();
        void onFailure(IOException error);

        /** The user cancelled the upload. */
        default void onCancelled() {
        }
    }

    /**
     * Connects and signs in ({@code mode} is "LOGIN" or "REGISTER"). Any failure, including a refused
     * login, is reported as an IOException whose message can be shown to the user.
     */
    public static ChatClient login(String host, int port, String mode, String account, String password)
            throws IOException {
        Socket socket = connectSocket(host, port);
        try {
            return new ChatClient(socket, mode, account, password);
        } catch (IOException | RuntimeException error) {
            try {
                socket.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private static Socket connectSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            return socket;
        } catch (IOException error) {
            try {
                socket.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private ChatClient(Socket socket, String mode, String account, String password) throws IOException {
        this.socket = socket;
        this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1024 * 1024));
        this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1024 * 1024));
        sendFrame(Frames.authRequest(mode, account, password));

        socket.setSoTimeout(10_000);
        byte type = input.readByte();
        input.readUTF();
        input.readUTF();
        String metadata = input.readUTF();
        byte[] data = new byte[0];
        if (ChatProtocol.carriesBytes(type)) {
            int length = input.readInt();
            if (length < 0 || length > 1024) {
                throw new IOException("Unexpected reply from the server.");
            }
            data = new byte[length];
            input.readFully(data);
        }
        if (type == ChatProtocol.TYPE_LOGIN_ERROR) {
            throw new IOException(metadata);
        }
        if (type != ChatProtocol.TYPE_AUTH) {
            throw new IOException("Unexpected reply from the server.");
        }
        socket.setSoTimeout(0);
        this.username = metadata;
        this.aiAvailable = data.length > 1 && data[1] != 0;
    }

    /** The account name exactly as registered (it may differ in capitalisation from what was typed). */
    public String username() {
        return username;
    }

    /** True when the server has an AI assistant plugged in. */
    public boolean aiAvailable() {
        return aiAvailable;
    }

    /** Must be called before {@link #startReading()}. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Asks the assistant for help; {@code kind} is one of ChatProtocol.AI_*, {@code conversation} "" for the group chat. */
    public void sendAiQuery(char kind, String conversation, String text) throws IOException {
        sendFrame(Frames.aiQuery(kind, conversation, text));
    }

    public void startReading() {
        Thread reader = new Thread(() -> {
            try {
                while (!closed) {
                    byte type = input.readByte();
                    if (type == ChatProtocol.TYPE_FILE_DATA) {
                        receiveFileData();
                        continue;
                    }
                    if (type == ChatProtocol.TYPE_FILE_DONE) {
                        finishDownload();
                        continue;
                    }
                    String sender = input.readUTF();
                    String timestamp = input.readUTF();
                    String metadata = input.readUTF();
                    long id = ChatProtocol.hasMessageId(type) ? input.readLong() : 0;
                    byte[] data = new byte[0];
                    if (ChatProtocol.carriesBytes(type)) {
                        int length = input.readInt();
                        if (length < 0 || length > ChatProtocol.MAX_ATTACHMENT_BYTES) {
                            throw new IOException("Received an invalid attachment size.");
                        }
                        data = new byte[length];
                        input.readFully(data);
                    }
                    ChatProtocol.Packet packet = new ChatProtocol.Packet(type, sender, timestamp, metadata, data, id);
                    SwingUtilities.invokeLater(() -> listener.onPacket(packet));
                }
            } catch (IOException error) {
                for (Download download : downloads.values()) { // nothing more will arrive for these
                    SwingUtilities.invokeLater(() -> download.listener.onFailure(error));
                }
                downloads.clear();
                if (!closed) {
                    SwingUtilities.invokeLater(() -> listener.onDisconnected(error));
                }
            }
        }, "nova-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void sendText(String text, String recipient) throws IOException {
        synchronized (output) {
            if (recipient == null) {
                output.writeByte(ChatProtocol.TYPE_TEXT);
                output.writeUTF(text);
            } else {
                output.writeByte(ChatProtocol.TYPE_PRIVATE);
                output.writeUTF(recipient);
                output.writeUTF(text);
            }
            output.flush();
        }
    }

    public void sendFrame(ChatProtocol.Packet packet) throws IOException {
        synchronized (output) {
            output.writeByte(packet.type);
            output.writeUTF(packet.sender);
            output.writeUTF(packet.timestamp);
            output.writeUTF(packet.metadata);
            if (ChatProtocol.carriesBytes(packet.type)) {
                output.writeInt(packet.data.length);
                output.write(packet.data);
            }
            output.flush();
        }
    }

    /** Edits one of my text messages. {@code peer} is null for GroupChat, else the other person of the private chat. */
    public void editMessage(String peer, long id, String newText) throws IOException {
        synchronized (output) {
            output.writeByte(ChatProtocol.TYPE_MSG_EDIT);
            output.writeUTF(peer == null ? "" : peer);
            output.writeLong(id);
            output.writeUTF(newText);
            output.flush();
        }
    }

    /** Deletes one of my messages for everybody. */
    public void deleteMessage(String peer, long id) throws IOException {
        synchronized (output) {
            output.writeByte(ChatProtocol.TYPE_MSG_DELETE);
            output.writeUTF(peer == null ? "" : peer);
            output.writeLong(id);
            output.flush();
        }
    }

    /** Tells everybody my status (a ChatProtocol.STATUS_* value). */
    public void sendStatus(int status) throws IOException {
        synchronized (output) {
            output.writeByte(ChatProtocol.TYPE_STATUS);
            output.writeByte(status);
            output.flush();
        }
    }

    /** Sets my profile picture (PNG bytes); the server shows it to everybody. */
    public void sendAvatar(byte[] png) throws IOException {
        synchronized (output) {
            output.writeByte(ChatProtocol.TYPE_AVATAR);
            output.writeInt(png.length);
            output.write(png);
            output.flush();
        }
    }

    // ---------------------------------------------------------------- big files: upload, cancel, download
    //
    // A file is never sent as one big packet. It is cut into chunks of 256 KB:
    //     FILE_BEGIN (who gets it, name, kind, size)  ->  FILE_CHUNK ... FILE_CHUNK  ->  FILE_END
    // The server writes the chunks to disk. Everybody in the chat then gets a message that points to the stored
    // file, and downloads it (FILE_GET) when they want it, again in chunks.

    /** A running upload. cancel() stops it before the next chunk; the server then throws away what it already got. */
    public static final class Upload {
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }
    }

    /** Uploads a file to GroupChat ({@code recipient} == null) or to one person. Returns at once; the work runs in the background. */
    public Upload sendFile(byte kind, File file, String recipient, TransferListener listener) throws IOException {
        if (!Files.isRegularFile(file.toPath()) || !Files.isReadable(file.toPath())) {
            throw new IOException("The selected file is not a readable regular file.");
        }
        return upload(kind, file.getName(), new FileInputStream(file), Files.size(file.toPath()), recipient, listener);
    }

    /** Uploads bytes that are already in memory (a recorded voice message). */
    public Upload sendBytes(byte kind, String name, byte[] data, String recipient, TransferListener listener) {
        return upload(kind, name, new ByteArrayInputStream(data), data.length, recipient, listener);
    }

    private Upload upload(byte kind, String name, InputStream source, long size, String recipient, TransferListener listener) {
        Upload upload = new Upload();
        TransferListener report = listener == null ? IGNORE : listener;
        Thread uploader = new Thread(() -> {
            try (InputStream file = source) {
                synchronized (output) {
                    output.writeByte(ChatProtocol.TYPE_FILE_BEGIN);
                    output.writeUTF(recipient == null ? "" : recipient);
                    output.writeUTF(name);
                    output.writeByte(kind);
                    output.writeLong(size);
                    output.flush();
                }
                byte[] chunk = new byte[ChatProtocol.FILE_CHUNK_BYTES];
                long sent = 0;
                long lastReport = 0;
                while (sent < size && !upload.cancelled) {
                    int count = file.read(chunk, 0, (int) Math.min(chunk.length, size - sent));
                    if (count < 0) {
                        throw new IOException("The file changed or ended during upload.");
                    }
                    synchronized (output) { // one chunk at a time, so chat messages can slip in between
                        output.writeByte(ChatProtocol.TYPE_FILE_CHUNK);
                        output.writeInt(count);
                        output.write(chunk, 0, count);
                        output.flush();
                    }
                    sent += count;
                    long now = System.nanoTime();
                    if (now - lastReport > 150_000_000L || sent == size) {
                        lastReport = now;
                        long done = sent;
                        SwingUtilities.invokeLater(() -> report.onProgress(done, size));
                    }
                }
                synchronized (output) {
                    output.writeByte(ChatProtocol.TYPE_FILE_END);
                    output.writeByte(upload.cancelled ? 0 : 1); // 0 tells the server to delete the unfinished file
                    output.flush();
                }
                SwingUtilities.invokeLater(upload.cancelled ? report::onCancelled : report::onComplete);
            } catch (IOException error) {
                try {
                    socket.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
                closed = true;
                SwingUtilities.invokeLater(() -> report.onFailure(error));
            }
        }, "nova-upload");
        uploader.setDaemon(true);
        uploader.start();
        return upload;
    }

    /** Does nothing: used when the caller does not care about the progress. */
    private static final TransferListener IGNORE = new TransferListener() {
        @Override
        public void onProgress(long done, long total) {
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onFailure(IOException error) {
        }
    };

    /** A file we asked the server for: its pieces are written to {@code target} by the reader thread. */
    private static final class Download {
        final OutputStream target;
        final long total;
        final TransferListener listener;
        long received;
        long lastReport;
        boolean failed;

        Download(OutputStream target, long total, TransferListener listener) {
            this.target = target;
            this.total = total;
            this.listener = listener;
        }
    }

    private final Map<Long, Download> downloads = new ConcurrentHashMap<Long, Download>();
    private final AtomicLong nextRequest = new AtomicLong();

    /** Asks the server for a stored file and writes it to {@code target} as it arrives; the stream is closed at the end. */
    public void download(long fileId, long total, OutputStream target, TransferListener listener) throws IOException {
        long request = nextRequest.incrementAndGet();
        downloads.put(request, new Download(target, total, listener));
        try {
            synchronized (output) {
                output.writeByte(ChatProtocol.TYPE_FILE_GET);
                output.writeLong(fileId);
                output.writeLong(request);
                output.flush();
            }
        } catch (IOException error) {
            downloads.remove(request);
            throw error;
        }
    }

    /** Reader thread: one piece of a requested file. */
    private void receiveFileData() throws IOException {
        long request = input.readLong();
        int length = input.readInt();
        if (length < 0 || length > ChatProtocol.MAX_CHUNK_BYTES) {
            throw new IOException("Received an invalid file chunk.");
        }
        byte[] piece = new byte[length];
        input.readFully(piece);
        Download download = downloads.get(request);
        if (download == null || download.failed) {
            return;
        }
        try {
            download.target.write(piece);
            download.received += length;
            long now = System.nanoTime();
            if (now - download.lastReport > 150_000_000L) {
                download.lastReport = now;
                long done = download.received;
                SwingUtilities.invokeLater(() -> download.listener.onProgress(done, download.total));
            }
        } catch (IOException error) {
            download.failed = true;
        }
    }

    /** Reader thread: the server has sent the whole file (or could not). */
    private void finishDownload() throws IOException {
        long request = input.readLong();
        boolean complete = input.readByte() == 1;
        Download download = downloads.remove(request);
        if (download == null) {
            return;
        }
        try {
            download.target.close();
        } catch (IOException error) {
            download.failed = true;
        }
        if (complete && !download.failed) {
            SwingUtilities.invokeLater(download.listener::onComplete);
        } else {
            SwingUtilities.invokeLater(() -> download.listener.onFailure(new IOException("The file could not be downloaded.")));
        }
    }

    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            synchronized (output) {
                output.writeByte(ChatProtocol.TYPE_TEXT);
                output.writeUTF("/quit");
                output.flush();
            }
        } catch (IOException ignored) {
            // Closing a failed or already disconnected socket is expected during shutdown.
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // The socket may already have been closed after a transfer failure.
        }
    }
}
