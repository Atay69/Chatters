package server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Private (one-to-one) messages - text and voice messages - saved so both people get them again
 * after signing in. New messages are appended to one file; an older text-only file, if there is
 * one, is still read.
 */
final class PrivateHistory {
    static final class Message {
        final String sender;
        final String recipient;
        final String timestamp;
        final String text;   // the message text ("" for a voice message)
        final byte[] audio;  // the voice message (a WAV file), or null for a text message

        Message(String sender, String recipient, String timestamp, String text, byte[] audio) {
            this.sender = sender;
            this.recipient = recipient;
            this.timestamp = timestamp;
            this.text = text;
            this.audio = audio;
        }

        boolean involves(String name) {
            return sender.equalsIgnoreCase(name) || recipient.equalsIgnoreCase(name);
        }
    }

    private final Path file;
    private final List<Message> messages = new ArrayList<Message>();

    PrivateHistory(Path oldTextFile, Path file) throws IOException {
        this.file = file;
        if (Files.exists(oldTextFile)) {
            try (DataInputStream in = open(oldTextFile)) {
                while (true) {
                    messages.add(new Message(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), null));
                }
            } catch (EOFException endOfFile) {
                // normal end of the old file
            }
        }
        if (Files.exists(file)) {
            try (DataInputStream in = open(file)) {
                while (true) {
                    String sender = in.readUTF();
                    String recipient = in.readUTF();
                    String timestamp = in.readUTF();
                    String text = in.readUTF();
                    int length = in.readInt();
                    byte[] audio = null;
                    if (length > 0) {
                        audio = new byte[length];
                        in.readFully(audio);
                    }
                    messages.add(new Message(sender, recipient, timestamp, text, audio));
                }
            } catch (EOFException endOfFile) {
                // normal end of the saved messages
            }
        }
    }

    private static DataInputStream open(Path path) throws IOException {
        return new DataInputStream(new BufferedInputStream(new FileInputStream(path.toFile())));
    }

    synchronized void add(String sender, String recipient, String timestamp, String text) throws IOException {
        save(new Message(sender, recipient, timestamp, text, null));
    }

    synchronized void addVoice(String sender, String recipient, String timestamp, byte[] wav) throws IOException {
        save(new Message(sender, recipient, timestamp, "", wav));
    }

    private void save(Message message) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file.toFile(), true)))) {
            out.writeUTF(message.sender);
            out.writeUTF(message.recipient);
            out.writeUTF(message.timestamp);
            out.writeUTF(message.text);
            out.writeInt(message.audio == null ? 0 : message.audio.length);
            if (message.audio != null) {
                out.write(message.audio);
            }
        }
        messages.add(message);
    }

    /** Every saved message the given user sent or received, oldest first. */
    synchronized List<Message> forUser(String name) {
        List<Message> result = new ArrayList<Message>();
        for (Message message : messages) {
            if (message.involves(name)) {
                result.add(message);
            }
        }
        return result;
    }

    /** The last {@code limit} text messages between two people, oldest first (for the AI). */
    synchronized List<Message> between(String first, String second, int limit) {
        List<Message> result = new ArrayList<Message>();
        for (Message message : messages) {
            if (message.audio == null && message.involves(first) && message.involves(second)) {
                result.add(message);
            }
        }
        return result.subList(Math.max(0, result.size() - limit), result.size());
    }
}
