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

/**
 * The edits and deletes of saved messages. Every change is added to the end of a small file as
 * (kind, message id, new text). When the server starts it replays the file over the saved messages,
 * so edited and deleted messages stay edited and deleted.
 */
final class ChangeLog {
    private static final byte EDIT = 1;
    private static final byte DELETE = 2;

    /** Receives the changes while the file is replayed. */
    interface Replay {
        void edited(long id, String newText);

        void deleted(long id);
    }

    private final Path file;

    ChangeLog(Path file) {
        this.file = file;
    }

    void replay(Replay target) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file.toFile())))) {
            while (true) {
                byte kind = in.readByte();
                long id = in.readLong();
                String text = in.readUTF();
                if (kind == EDIT) {
                    target.edited(id, text);
                } else {
                    target.deleted(id);
                }
            }
        } catch (EOFException endOfFile) {
            // normal end of the file
        }
    }

    void edit(long id, String newText) throws IOException {
        append(EDIT, id, newText);
    }

    void delete(long id) throws IOException {
        append(DELETE, id, "");
    }

    private synchronized void append(byte kind, long id, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file.toFile(), true)))) {
            out.writeByte(kind);
            out.writeLong(id);
            out.writeUTF(text);
        }
    }
}
