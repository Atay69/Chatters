package client;

import shared.ChatProtocol;
import shared.Frames;

import javax.swing.SwingUtilities;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.net.Socket;

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
        this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
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
                    String sender = input.readUTF();
                    String timestamp = input.readUTF();
                    String metadata = input.readUTF();
                    byte[] data = new byte[0];
                    if (ChatProtocol.carriesBytes(type)) {
                        int length = input.readInt();
                        if (length < 0 || length > ChatProtocol.MAX_ATTACHMENT_BYTES) {
                            throw new IOException("Received an invalid attachment size.");
                        }
                        data = new byte[length];
                        input.readFully(data);
                    }
                    ChatProtocol.Packet packet = new ChatProtocol.Packet(type, sender, timestamp, metadata, data);
                    SwingUtilities.invokeLater(() -> listener.onPacket(packet));
                }
            } catch (IOException error) {
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

    public void sendAudio(byte[] data) throws IOException {
        synchronized (output) {
            output.writeByte(ChatProtocol.TYPE_AUDIO);
            output.writeUTF("voice_message.wav");
            output.writeInt(data.length);
            output.write(data);
            output.flush();
        }
    }

    /** A voice message for one person (private chat). */
    public void sendPrivateAudio(String recipient, byte[] data) throws IOException {
        synchronized (output) {
            output.writeByte(ChatProtocol.TYPE_PRIVATE_AUDIO);
            output.writeUTF(recipient);
            output.writeUTF("voice_message.wav");
            output.writeInt(data.length);
            output.write(data);
            output.flush();
        }
    }

    public void sendFile(byte type, File file, TransferListener transferListener) throws IOException {
        if (type != ChatProtocol.TYPE_IMAGE && type != ChatProtocol.TYPE_FILE) {
            throw new IllegalArgumentException("Only image and file attachments can be uploaded here.");
        }
        if (!Files.isRegularFile(file.toPath()) || !Files.isReadable(file.toPath())) {
            throw new IOException("The selected file is not a readable regular file.");
        }
        long length = Files.size(file.toPath());
        if (length > ChatProtocol.MAX_ATTACHMENT_BYTES) {
            throw new IOException("Files must be 50 MB or smaller.");
        }
        Thread uploader = new Thread(() -> {
            try (BufferedInputStream fileInput = new BufferedInputStream(new FileInputStream(file))) {
                synchronized (output) {
                    output.writeByte(type);
                    output.writeUTF(file.getName());
                    output.writeInt((int) length);
                    byte[] buffer = new byte[64 * 1024];
                    long transferred = 0;
                    long lastReported = 0;
                    long reportInterval = Math.max(buffer.length, length / 100);
                    while (transferred < length) {
                        int count = fileInput.read(buffer, 0,
                                (int) Math.min(buffer.length, length - transferred));
                        if (count < 0) {
                            throw new IOException("The file changed or ended during upload.");
                        }
                        output.write(buffer, 0, count);
                        transferred += count;
                        if (transferred - lastReported >= reportInterval || transferred == length) {
                            long progress = transferred;
                            lastReported = transferred;
                            SwingUtilities.invokeLater(() -> transferListener.onProgress(progress, length));
                        }
                    }
                    if (fileInput.read() != -1) {
                        throw new IOException("The file changed while it was being sent.");
                    }
                    output.flush();
                }
                SwingUtilities.invokeLater(transferListener::onComplete);
            } catch (IOException error) {
                try {
                    socket.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
                closed = true;
                SwingUtilities.invokeLater(() -> transferListener.onFailure(error));
            }
        }, "nova-upload");
        uploader.setDaemon(true);
        uploader.start();
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
