package ai;

import java.util.List;

/**
 * The "brain" of the chat's AI. {@link GroqAssistant} is the one that is used; to plug in another
 * one, implement this interface (public no-argument constructor) and start the server with
 *
 *     -Dchat.aiClass=your.package.YourAssistant
 *
 * What users get from it:
 *   - ask            : the "AI Assistant" conversation, the "/ai question" command and the sparkle button
 *   - suggestReplies : clickable reply chips above the message box
 *   - completeText   : press TAB in the message box to continue your sentence
 *
 * Calls run on background threads. Throw any exception to signal failure; the chat keeps working.
 */
public interface AiAssistant {

    /** false = the AI is switched off (e.g. no API key): the buttons stay hidden. */
    default boolean isAvailable() {
        return true;
    }

    /** Up to about three short reply options for the conversation in {@code ctx.recentMessages()}. */
    List<String> suggestReplies(AiContext ctx) throws Exception;

    /** The text that should FOLLOW {@code ctx.input()} (do not repeat the input). */
    String completeText(AiContext ctx) throws Exception;

    /** The answer to {@code ctx.input()}, a question or request from the user. */
    String ask(AiContext ctx) throws Exception;
}
