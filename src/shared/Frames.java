package shared;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/** Builds the packets that are not plain text, so that client and server create them the same way. */
public final class Frames {
    private Frames() {
    }

    /** A login request. The password is sent as plain bytes: on a real network use an encrypted (TLS) connection. */
    public static ChatProtocol.Packet authRequest(String username, String password) {
        return authRequest("LOGIN", username, password);
    }

    /** First frame a client sends. {@code mode} is "LOGIN" or "REGISTER" (carried in the metadata). */
    public static ChatProtocol.Packet authRequest(String mode, String username, String password) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(username);
                output.writeUTF(password);
            }
            return new ChatProtocol.Packet(ChatProtocol.TYPE_AUTH, username, "", mode, bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode auth frame.", impossible);
        }
    }

    /** Server answer to a successful login: the account name, and whether an AI assistant is plugged in. */
    public static ChatProtocol.Packet authOk(String accountName, boolean aiAvailable) {
        return new ChatProtocol.Packet(ChatProtocol.TYPE_AUTH, "SYSTEM", "", accountName,
                new byte[] { 1, (byte) (aiAvailable ? 1 : 0) });
    }

    /** Asks the assistant for help. {@code conversation} is "" for the group chat, else the private-chat partner. */
    public static ChatProtocol.Packet aiQuery(char kind, String conversation, String text) {
        return new ChatProtocol.Packet(ChatProtocol.TYPE_AI_QUERY, conversation, "", kind + text, new byte[0]);
    }

    /** Assistant answer; the metadata starts with the kind character of the query it answers. */
    public static ChatProtocol.Packet aiReply(char kind, String text, String timestamp) {
        return new ChatProtocol.Packet(ChatProtocol.TYPE_AI_REPLY, "AI", timestamp, kind + text, new byte[] { 1 });
    }

    public static ChatProtocol.Packet presence(String name, boolean online) {
        return new ChatProtocol.Packet(ChatProtocol.TYPE_USER_PRESENCE, "SYSTEM", "", name,
                new byte[] { (byte) (online ? 1 : 0) });
    }

    public static ChatProtocol.Packet userList(List<ChatProtocol.UserRecord> users) {
        try {
            return new ChatProtocol.Packet(ChatProtocol.TYPE_USER_LIST, "SYSTEM", "", "",
                    ChatProtocol.encodeUserList(users));
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not encode user list.", error);
        }
    }

    public static ChatProtocol.Packet aiReply(String text, boolean done) {
        return new ChatProtocol.Packet(ChatProtocol.TYPE_AI_REPLY, "AI", "", text,
                new byte[] { (byte) (done ? 1 : 0) });
    }
}
