package shared;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ProtocolFramesTest {
    public static void main(String[] args) throws Exception {
        List<ChatProtocol.UserRecord> users = Arrays.asList(
                new ChatProtocol.UserRecord("Online", true),
                new ChatProtocol.UserRecord("Offline", false));
        List<ChatProtocol.UserRecord> decoded = ChatProtocol.decodeUserList(ChatProtocol.encodeUserList(users));
        if (decoded.size() != 2 || !decoded.get(0).online || decoded.get(1).online) {
            throw new AssertionError("User-list online states did not round-trip.");
        }

        ChatProtocol.Packet presence = Frames.presence("Online", true);
        if (presence.type != ChatProtocol.TYPE_USER_PRESENCE
                || !presence.metadata.equals("Online") || presence.data[0] != 1) {
            throw new AssertionError("Presence frame did not encode online state.");
        }

        ChatProtocol.Packet auth = Frames.authRequest("user", "secret");
        if (auth.type != ChatProtocol.TYPE_AUTH || auth.data.length == 0) {
            throw new AssertionError("Auth request frame is missing its payload.");
        }
        ChatProtocol.Packet reply = Frames.aiReply("done", true);
        if (reply.type != ChatProtocol.TYPE_AI_REPLY || reply.data[0] != 1) {
            throw new AssertionError("AI reply frame did not carry its completion flag.");
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> response = new AtomicReference<String>();
        ai.AiService.ask("/ai test", response::set, done::countDown);
        if (!done.await(3, TimeUnit.SECONDS) || !"AI not configured yet.".equals(response.get())) {
            throw new AssertionError("AI stub did not complete asynchronously.");
        }
        System.out.println("Protocol frame checks passed.");
    }
}
