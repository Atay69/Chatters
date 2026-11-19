package ai;

import java.util.List;

/** Default placeholder used until a real assistant is configured. It does nothing. */
public final class NoAssistant implements AiAssistant {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<String> suggestReplies(AiContext ctx) {
        return List.of();
    }

    @Override
    public String completeText(AiContext ctx) {
        return "";
    }

    @Override
    public String ask(AiContext ctx) {
        return "AI not configured yet.";
    }
}
