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
import java.util.Iterator;
import java.util.List;

/**
 * Private (one-to-one) messages - text, stored files and old voice messages - saved so both people get
 * them again after signing in. New messages are appended to one file; an older text-only file, if there
 * is one, is still read. Every message has an id (its position in the files, starting at 1) so it can be
 * edited or deleted; those changes are kept in a second small file (see ChangeLog).
 */
final class PrivateHistory {
    static final class Message {
        final long id;
        final String sender;
        final String recipient;
        final String timestamp;
        volatile String text;     // the message text ("" for an old voice message)
        final byte[] audio;       // an old voice message (a WAV file), or null
        volatile boolean edited;

        Message(long id, String sender, String recipient, String timestamp, String text, byte[] audio) {
            this.id = id;
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
    private final ChangeLog changes;
    private final List<Message> messages = new ArrayList<Message>();
    private long lastId;

    PrivateHistory(Path oldTextFile, Path file) throws IOException {
        this.file = file;
        this.changes = new ChangeLog(file.resolveSibling("private-changes.bin"));
        if (Files.exists(oldTextFile)) {
            try (DataInputStream in = open(oldTextFile)) {
                while (true) {
                    messages.add(new Message(++lastId, in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), null));
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
                    messages.add(new Message(++lastId, sender, recipient, timestamp, text, audio));
                }
            } catch (EOFException endOfFile) {
                // normal end of the saved messages
            }
        }
        changes.replay(new ChangeLog.Replay() { // edits and deletes made earlier
            @Override
            public void edited(long id, String newText) {
                Message message = find(id);
                if (message != null) {
                    message.text = newText;
                    message.edited = true;
                }
            }

            @Override
            public void deleted(long id) {
                Message message = find(id);
                if (message != null) {
                    messages.remove(message);
                }
            }
        });
    }

    private static DataInputStream open(Path path) throws IOException {
        return new DataInputStream(new BufferedInputStream(new FileInputStream(path.toFile())));
    }

    /** Saves a new text (or stored-file) message and returns it, with its id. */
    synchronized Message add(String sender, String recipient, String timestamp, String text) throws IOException {
        Message message = new Message(lastId + 1, sender, recipient, timestamp, text, null);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file.toFile(), true)))) {
            out.writeUTF(message.sender);
            out.writeUTF(message.recipient);
            out.writeUTF(message.timestamp);
            out.writeUTF(message.text);
            out.writeInt(0);
        }
        lastId++;
        messages.add(message);
        return message;
    }

    /** The message with this id, or null (never saved, or deleted). */
    synchronized Message find(long id) {
        for (Message message : messages) {
            if (message.id == id) {
                return message;
            }
        }
        return null;
    }

    synchronized void edit(Message message, String newText) throws IOException {
        changes.edit(message.id, newText);
        message.text = newText;
        message.edited = true;
    }

    synchronized void delete(Message message) throws IOException {
        changes.delete(message.id);
        for (Iterator<Message> it = messages.iterator(); it.hasNext(); ) {
            if (it.next() == message) {
                it.remove();
            }
        }
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
