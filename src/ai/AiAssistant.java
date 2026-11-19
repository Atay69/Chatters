package ai;

import java.util.List;

/**
 * ===========================  AI PLUG-IN POINT  ===========================
 *
 * The chat already knows how to ask an assistant for help; it just has no brain yet.
 * When your model is ready, implement this interface (template: MyAssistant.java) and
 * start the server with the VM option:
 *
 *     -Dchat.aiClass=ai.MyAssistant
 *
 * (IntelliJ: Run > Edit Configurations > Server > Modify options > Add VM options)
 *
 * What users get once an assistant is connected:
 *   - ask            : the "/ai question" command and the sparkles button
 *   - suggestReplies : clickable reply chips above the message box
 *   - completeText   : press TAB in the message box to continue your sentence
 *
 * Calls run on background threads, never on a network reader thread. Throw any
 * exception to signal failure; the chat keeps working.
 * ==========================================================================
 */
public interface AiAssistant {

    /** false = the chat reports "AI not configured yet." instead of calling the methods below. */
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
