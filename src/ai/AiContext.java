package ai;

import java.util.List;

/**
 * Everything the assistant may look at for one request.
 *
 * @param user           who is asking
 * @param conversation   "" for the group chat, otherwise the name of the private-chat partner
 * @param input          the user's text (question or draft); empty for reply suggestions
 * @param recentMessages the latest messages of this conversation, oldest first, as "sender: text"
 */
public record AiContext(String user, String conversation, String input, List<String> recentMessages) {
}
