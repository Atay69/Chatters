package ai;

import server.ProfanityFilter;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs assistant requests on a small background pool, so the chat never waits for the AI.
 * The assistant is {@link GroqAssistant} unless the server is started with -Dchat.aiClass=your.Class.
 */
public final class AiService {
    public enum Kind { SUGGEST, COMPLETE, ASK }

    /** What a question gets back when no API key is set (the assistant is switched off). */
    private static final String NOT_CONFIGURED = "AI not configured yet.";

    /** Two worker threads and a waiting line of 32; a full line makes {@link #request} throw "busy". */
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "ai-worker");
                thread.setDaemon(true);
                return thread;
            });

    private static final AiAssistant ASSISTANT = load();

    private AiService() {
    }

    /** True when the assistant can answer (clients then show the AI buttons and suggestions). */
    public static boolean isAvailable() {
        return ASSISTANT.isAvailable();
    }

    /** Shortcut for a plain question: {@code onChunk} receives the answer, then {@code onDone} runs. */
    public static Future<?> ask(String prompt, Consumer<String> onChunk, Runnable onDone) {
        return request(Kind.ASK, new AiContext("", "", prompt, List.of()), onChunk, onDone);
    }

    /**
     * Runs one request in the background. {@code onResult} gets the text (suggestions are joined with newlines)
     * and {@code onDone} always runs afterwards. Cancel the returned task when the client disconnects.
     *
     * @throws java.util.concurrent.RejectedExecutionException when the waiting line is full
     */
    public static Future<?> request(Kind kind, AiContext ctx, Consumer<String> onResult, Runnable onDone) {
        return EXECUTOR.submit(() -> {
            try {
                ProfanityFilter.filter(ctx.input());
                String result = run(kind, ctx);
                if (!result.isEmpty()) {
                    onResult.accept(result);
                }
            } catch (Exception error) {
                if (kind == Kind.ASK) { // only a direct question deserves an error message
                    onResult.accept("The assistant failed: " + error.getMessage());
                }
            } finally {
                onDone.run();
            }
        });
    }

    /** Calls the matching method of the assistant. */
    private static String run(Kind kind, AiContext ctx) throws Exception {
        if (!ASSISTANT.isAvailable()) {
            return kind == Kind.ASK ? NOT_CONFIGURED : "";
        }
        switch (kind) {
            case SUGGEST:  return String.join("\n", ASSISTANT.suggestReplies(ctx));
            case COMPLETE: return ASSISTANT.completeText(ctx);
            default:       return ASSISTANT.ask(ctx);
        }
    }

    /** Creates the assistant: the class named by -Dchat.aiClass, or GroqAssistant. */
    private static AiAssistant load() {
        String className = System.getProperty("chat.aiClass", "");
        if (!className.isBlank()) {
            try {
                return (AiAssistant) Class.forName(className.trim()).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | ClassCastException error) {
                System.err.println("Could not load AI class '" + className + "': " + error + " - using GroqAssistant");
            }
        }
        return new GroqAssistant();
    }
}
