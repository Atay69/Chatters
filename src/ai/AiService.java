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
 * Runs assistant requests on a small background pool and loads the configured
 * {@link AiAssistant} (-Dchat.aiClass=ai.MyAssistant; {@link NoAssistant} when unset).
 */
public final class AiService {
    public enum Kind { SUGGEST, COMPLETE, ASK }

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "nova-ai");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private static final AiAssistant ASSISTANT = load();

    private AiService() {
    }

    /** True when a real assistant is plugged in (clients then show suggestions and the AI button). */
    public static boolean isAvailable() {
        return ASSISTANT.isAvailable();
    }

    /** Original entry point: answer a prompt; {@code onChunk} receives the reply, then {@code onDone} runs. */
    public static Future<?> ask(String prompt, Consumer<String> onChunk, Runnable onDone) {
        return request(Kind.ASK, new AiContext("", "", prompt, List.of()), onChunk, onDone);
    }

    /**
     * Runs one request. {@code onResult} gets the text (suggestions are joined with newlines),
     * {@code onDone} always runs afterwards. Cancel the returned task when the client disconnects.
     *
     * @throws java.util.concurrent.RejectedExecutionException when the queue is full
     */
    public static Future<?> request(Kind kind, AiContext ctx, Consumer<String> onResult, Runnable onDone) {
        return EXECUTOR.submit(() -> {
            try {
                ProfanityFilter.filter(ctx.input());
                String result = run(kind, ctx);
                if (result != null && !result.isEmpty()) {
                    onResult.accept(result);
                }
            } catch (Exception error) {
                if (kind == Kind.ASK) {
                    onResult.accept("The assistant failed: " + error.getMessage());
                }
            } finally {
                onDone.run();
            }
        });
    }

    private static String run(Kind kind, AiContext ctx) throws Exception {
        switch (kind) {
            case SUGGEST:
                return ASSISTANT.isAvailable() ? String.join("\n", ASSISTANT.suggestReplies(ctx)) : "";
            case COMPLETE:
                return ASSISTANT.isAvailable() ? ASSISTANT.completeText(ctx) : "";
            default:
                return ASSISTANT.ask(ctx);
        }
    }

    private static AiAssistant load() {
        String className = System.getProperty("chat.aiClass");
        if (className != null && !className.trim().isEmpty()) {
            try {
                AiAssistant assistant = (AiAssistant) Class.forName(className.trim())
                        .getDeclaredConstructor().newInstance();
                System.out.println("AI assistant loaded: " + className.trim());
                return assistant;
            } catch (ReflectiveOperationException | ClassCastException error) {
                System.err.println("Could not load AI class '" + className + "': " + error + " - AI disabled");
            }
        }
        return new NoAssistant();
    }
}
