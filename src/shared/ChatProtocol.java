package shared;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The language client and server speak. Every message is one "packet":
 *
 *     type (1 byte), sender, timestamp, metadata (3 texts), and - for some types - a block of bytes.
 *
 * What "metadata" and the bytes mean depends on the type, for example:
 *     TYPE_TEXT     metadata = the message text
 *     TYPE_FILE     metadata = the file name, bytes = the file
 *     TYPE_PRIVATE  metadata = the recipient, bytes = the message text
 *     TYPE_AUTH     (client -> server) metadata = "LOGIN" or "REGISTER", bytes = name + password
 * {@link #carriesBytes} says which types have the byte block.
 */
public final class ChatProtocol {
    /** The biggest file one packet can carry: about 2 GB, the most a Java array (and the int length in the packet) can hold. */
    public static final int MAX_ATTACHMENT_BYTES = Integer.MAX_VALUE - 8;
    public static final byte TYPE_TEXT = 1;
    public static final byte TYPE_IMAGE = 2;
    public static final byte TYPE_FILE = 3;
    public static final byte TYPE_AUDIO = 4;
    public static final byte TYPE_SYSTEM = 5;           // "X joined the chat"
    public static final byte TYPE_TRANSFER_STATUS = 7;  // server -> one client: "Transfer accepted", errors
    public static final byte TYPE_PRIVATE_AUDIO = 8;    // a voice message for one person (metadata = the recipient)
    public static final byte TYPE_PRIVATE = 9;
    public static final byte TYPE_LOGIN_ERROR = 10;
    public static final byte TYPE_AUTH = 11;
    public static final byte TYPE_USER_PRESENCE = 12;   // somebody came online / went offline
    public static final byte TYPE_USER_LIST = 13;       // everybody with online/offline
    public static final byte TYPE_AI_QUERY = 14;        // client -> server: question for the AI
    public static final byte TYPE_AI_REPLY = 15;        // server -> client: answer from the AI
    public static final byte TYPE_AVATAR = 16;          // a profile picture (PNG bytes; the sender is its owner)
    public static final int MAX_AVATAR_BYTES = 256 * 1024;
    public static final byte TYPE_STATUS = 17;          // client -> server: my new status (one byte, see STATUS_*)

    /** What a person shows to the others. INVISIBLE is chosen by a client but others are told OFFLINE. */
    public static final int STATUS_OFFLINE = 0;
    public static final int STATUS_ONLINE = 1;
    public static final int STATUS_AWAY = 2;
    public static final int STATUS_BUSY = 3;        // "Do not disturb"
    public static final int STATUS_INVISIBLE = 4;

    // ---- big files: sent in small chunks, stored on the server, downloaded on demand ----
    public static final byte TYPE_FILE_BEGIN = 18;  // client -> server: UTF recipient ("" = GroupChat), UTF file name, byte kind, long size
    public static final byte TYPE_FILE_CHUNK = 19;  // client -> server: int length, then that many bytes of the file
    public static final byte TYPE_FILE_END = 20;    // client -> server: byte 1 = the whole file was sent, 0 = upload cancelled
    public static final byte TYPE_FILE_GET = 21;    // client -> server: long file id, long request number (please send me this file)
    public static final byte TYPE_FILE_DATA = 22;   // server -> client: long request number, int length, then a piece of the file
    public static final byte TYPE_FILE_DONE = 23;   // server -> client: long request number, byte 1 = complete, 0 = failed

    // ---- editing and deleting messages (only your own) ----
    public static final byte TYPE_MSG_EDIT = 24;     // client -> server: UTF conversation ("" = GroupChat, else the other person), long message id, UTF new text
    public static final byte TYPE_MSG_DELETE = 25;   // client -> server: UTF conversation, long message id
    public static final byte TYPE_MSG_EDITED = 26;   // server -> client: sender = author, metadata = recipient ("" = GroupChat), id, bytes = the new text
    public static final byte TYPE_MSG_DELETED = 27;  // server -> client: sender = author, metadata = recipient ("" = GroupChat), id

    /** Message packets carry the id of the message (a long written after the metadata), so it can be edited or deleted later. */
    public static boolean hasMessageId(byte type) {
        return type == TYPE_TEXT || type == TYPE_IMAGE || type == TYPE_FILE || type == TYPE_AUDIO
                || type == TYPE_PRIVATE || type == TYPE_PRIVATE_AUDIO || type == TYPE_MSG_EDITED || type == TYPE_MSG_DELETED;
    }

    /** Size of the pieces a file is cut into (never more than MAX_CHUNK_BYTES). */
    public static final int FILE_CHUNK_BYTES = 256 * 1024;
    public static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;

    /**
     * A message that starts with this character is not text but a stored file:
     *     FILE_MARK + id + "|" + kind + "|" + size + "|" + file name        (kind = TYPE_IMAGE, TYPE_FILE or TYPE_AUDIO)
     * It travels and is saved like any text message (in GroupChat and in private chats).
     */
    public static final String FILE_MARK = "\u0001";

    public static String fileMessage(long id, byte kind, long size, String name) {
        return FILE_MARK + id + "|" + kind + "|" + size + "|" + name;
    }

    /** First character of an AI query / AI reply text: what the assistant is asked to do. */
    public static final char AI_SUGGEST = 'S';
    public static final char AI_COMPLETE = 'C';
    public static final char AI_ASK = 'A';      // one-off question ("/ai ...", sparkle button); the answer is a notice
    public static final char AI_CHAT = 'T';     // a message in the dedicated AI conversation; the answer is a chat message

    /** The name of the dedicated AI conversation in the sidebar (it looks like a person, but it lives on the client). */
    public static final String AI_NAME = "AI Assistant";

    private ChatProtocol() {
    }

    public static boolean carriesBytes(byte type) {
        return type == TYPE_IMAGE || type == TYPE_FILE || type == TYPE_AUDIO || type == TYPE_PRIVATE
                || type == TYPE_PRIVATE_AUDIO || type == TYPE_AUTH || type == TYPE_USER_PRESENCE || type == TYPE_USER_LIST
                || type == TYPE_AI_REPLY || type == TYPE_AVATAR || type == TYPE_MSG_EDITED;
    }

    public static byte[] encodeUserList(List<UserRecord> users) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(users.size());
            for (UserRecord user : users) {
                output.writeUTF(user.displayName);
                output.writeByte(user.status);
            }
        }
        return bytes.toByteArray();
    }

    public static List<UserRecord> decodeUserList(byte[] bytes) throws IOException {
        List<UserRecord> users = new ArrayList<UserRecord>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = input.readInt();
            if (count < 0 || count > 10000) {
                throw new IOException("Invalid user-list size.");
            }
            for (int i = 0; i < count; i++) {
                users.add(new UserRecord(input.readUTF(), input.readByte()));
            }
            if (input.available() != 0) {
                throw new IOException("Unexpected trailing user-list data.");
            }
        }
        return users;
    }

    public static final class Packet {
        public final byte type;
        public final String sender;
        public final String timestamp;
        public final String metadata;
        public final byte[] data;
        public final long id;     // the id of a chat message (see hasMessageId); 0 for other packets

        public Packet(byte type, String sender, String timestamp, String metadata, byte[] data) {
            this(type, sender, timestamp, metadata, data, 0);
        }

        public Packet(byte type, String sender, String timestamp, String metadata, byte[] data, long id) {
            this.id = id;
            this.type = type;
            this.sender = sender;
            this.timestamp = timestamp;
            this.metadata = metadata;
            this.data = data;
        }

    }

    public static final class UserRecord {
        public final String displayName;
        public final int status;      // one of the STATUS_* values
        public final boolean online;  // anything except OFFLINE

        public UserRecord(String displayName, int status) {
            this.displayName = displayName;
            this.status = status;
            this.online = status != STATUS_OFFLINE;
        }

        public UserRecord(String displayName, boolean online) {
            this(displayName, online ? STATUS_ONLINE : STATUS_OFFLINE);
        }
    }
}
