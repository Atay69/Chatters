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
    public static final int MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024;
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

    /** First character of an AI query / AI reply text: what the assistant is asked to do. */
    public static final char AI_SUGGEST = 'S';
    public static final char AI_COMPLETE = 'C';
    public static final char AI_ASK = 'A';

    private ChatProtocol() {
    }

    public static boolean carriesBytes(byte type) {
        return type == TYPE_IMAGE || type == TYPE_FILE || type == TYPE_AUDIO || type == TYPE_PRIVATE
                || type == TYPE_PRIVATE_AUDIO || type == TYPE_AUTH || type == TYPE_USER_PRESENCE || type == TYPE_USER_LIST
                || type == TYPE_AI_REPLY;
    }

    public static byte[] encodeUserList(List<UserRecord> users) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(users.size());
            for (UserRecord user : users) {
                output.writeUTF(user.displayName);
                output.writeBoolean(user.online);
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
                users.add(new UserRecord(input.readUTF(), input.readBoolean()));
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

        public Packet(byte type, String sender, String timestamp, String metadata, byte[] data) {
            this.type = type;
            this.sender = sender;
            this.timestamp = timestamp;
            this.metadata = metadata;
            this.data = data;
        }

    }

    public static final class UserRecord {
        public final String displayName;
        public final boolean online;

        public UserRecord(String displayName, boolean online) {
            this.displayName = displayName;
            this.online = online;
        }
    }
}
