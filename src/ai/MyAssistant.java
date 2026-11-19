package ai;

import java.util.List;

/**
 * TEMPLATE for your own AI. It is not active until the server is started with
 *     -Dchat.aiClass=ai.MyAssistant
 *
 * Replace the TODOs with calls to your model (local model, REST API, Python process ...).
 * The class needs a public no-argument constructor. As written it returns harmless canned
 * answers so you can see the interface working before the real model exists.
 */
public class MyAssistant implements AiAssistant {

    @Override
    public List<String> suggestReplies(AiContext ctx) {
        // TODO: feed ctx.recentMessages() to your model and return up to 3 short replies.
        return List.of("Sounds good!", "Tell me more", "Thanks!");
    }

    @Override
    public String completeText(AiContext ctx) {
        // TODO: return only the continuation of ctx.input().
        return " ...";
    }

    @Override
    public String ask(AiContext ctx) {
        // TODO: answer ctx.input(); ctx.recentMessages() can serve as context.
        return "Hi " + ctx.user() + "! My real brain is not plugged in yet. You asked: " + ctx.input();
    }
}
