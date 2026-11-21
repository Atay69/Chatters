package ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A REAL AI assistant that uses Groq's hosted models. Groq has a FREE tier (rate limited).
 *
 * HOW TO TURN IT ON (all on the SERVER computer):
 *   1. Make a free account at https://console.groq.com and create an API key (API Keys > Create).
 *   2. Put the key in an environment variable called GROQ_API_KEY
 *      (IntelliJ: Run > Edit Configurations > Server > Environment variables).
 *      NEVER write the key inside this file - it would end up in git.
 *   3. Start the server (this class is the default, no other option is needed).
 *   4. In the chat open "AI Assistant" in the sidebar, or type "/ai hello" in any chat.
 *
 * HOW IT WORKS (3 steps):
 *   a) Each feature (ask / suggest / complete) builds a short text prompt.
 *   b) {@link #callGroq} sends it as JSON to Groq and gets JSON back.
 *   c) {@link #extractText} pulls the answer text out of that JSON.
 *
 * Groq uses the same request format as OpenAI ("chat completions"), so this class is also
 * a template for any OpenAI-compatible service: change URL, MODEL and the key variable.
 * No libraries needed: Java's built-in HttpClient (Java 11+) and a tiny hand-made JSON writer/reader.
 *
 * THINGS YOU MAY WANT TO CHANGE: MODEL, SYSTEM (the personality), the maxTokens in each method.
 */
public class GroqAssistant implements AiAssistant {

    /**
     * Which model answers. Groq renames/retires models from time to time (a "404 model_not_found" error means
     * this name is gone). See the current list at https://console.groq.com/docs/models, or ask the API:
     * GET https://api.groq.com/openai/v1/models with your key. "openai/gpt-oss-120b" is the bigger, smarter one.
     */
    private static final String MODEL = "openai/gpt-oss-20b";

    /** Groq's address (OpenAI-compatible). */
    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    /** The assistant's personality and rules. Edit freely. */
    private static final String SYSTEM =
            "You are a friendly assistant inside a small chat app. Keep answers short (1-3 sentences) "
            + "and plain text only: no markdown.";

    /** Read once at start-up; null when the variable is missing. */
    private static final String API_KEY = trimmed(System.getenv("GROQ_API_KEY"));

    /** One reusable connection helper. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** The chat shows the assistant buttons only when this is true (no key = feature switched off). */
    @Override
    public boolean isAvailable() {
        return API_KEY != null && !API_KEY.isBlank();
    }

    /** The "/ai question" command and the sparkle button. */
    @Override
    public String ask(AiContext ctx) throws Exception {
        String prompt = "Recent chat:\n" + history(ctx)
                + "\nThe user " + ctx.user() + " asks you: " + ctx.input();
        return callGroq(prompt, 800);   // limits include the model's hidden thinking, so keep them generous
    }

    /** The clickable reply chips: we ask for 3 replies, one per line, and split them. */
    @Override
    public List<String> suggestReplies(AiContext ctx) throws Exception {
        String prompt = "Recent chat:\n" + history(ctx)
                + "\nWrite 3 very short replies (max 6 words each) that " + ctx.user()
                + " could send next. Output exactly one reply per line, nothing else.";
        List<String> replies = new ArrayList<>();
        for (String line : callGroq(prompt, 400).split("\n")) {
            if (!line.isBlank() && replies.size() < 3) {
                replies.add(line.trim());
            }
        }
        return replies;
    }

    /** TAB in the message box: return only the text that should FOLLOW what was typed. */
    @Override
    public String completeText(AiContext ctx) throws Exception {
        String prompt = "Recent chat:\n" + history(ctx)
                + "\nContinue this unfinished message with a few words. Output ONLY the continuation, "
                + "do not repeat the start: " + ctx.input();
        return callGroq(prompt, 300);
    }

    // ------------------------------------------------------------------ helpers

    /** Removes accidental spaces around the key (a space at the end of the variable breaks the login). */
    private static String trimmed(String text) {
        return text == null ? null : text.trim();
    }

    /** The last chat messages as plain lines "sender: text" (the server already limits how many). */
    private static String history(AiContext ctx) {
        return ctx.recentMessages().isEmpty() ? "(no messages yet)" : String.join("\n", ctx.recentMessages());
    }

    /**
     * Sends ONE prompt to Groq and returns the answer text.
     *
     * @param maxTokens upper limit for the answer length (about 1 token = 3/4 of a word)
     * @throws Exception on network problems or when Groq answers with an error; AiService
     *                   catches it and tells the user "The assistant failed: ..."
     */
    private static String callGroq(String prompt, int maxTokens) throws Exception {
        // The request body: the personality ("system") and the question ("user").
        String body = "{\"model\":" + quote(MODEL)
                + ",\"max_tokens\":" + maxTokens
                + ",\"reasoning_effort\":\"low\""           // this model "thinks" first; low = quick
                + ",\"messages\":["
                + "{\"role\":\"system\",\"content\":" + quote(SYSTEM) + "},"
                + "{\"role\":\"user\",\"content\":" + quote(prompt) + "}]}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + API_KEY)   // this is how Groq knows who you are
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // 401 = wrong key, 429 = free limit reached (wait a minute), 400 = model name wrong ...
            throw new Exception("API error " + response.statusCode() + ": " + response.body());
        }
        return extractText(response.body()).trim();
    }

    /** Wraps a Java string in quotes and escapes characters JSON does not allow raw (quote, backslash, newline...). */
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); // other control characters
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * Groq answers like  {"choices":[{"message":{"role":"assistant","content":"Hello!"}}], ...}
     * We find the first  "content":"  and read the string that follows, undoing the JSON escapes.
     */
    private static String extractText(String json) throws Exception {
        String marker = "\"content\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new Exception("No text in the API answer: " + json);
        }
        StringBuilder out = new StringBuilder();
        for (int i = start + marker.length(); i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {                       // an unescaped quote ends the string
                break;
            } else if (c != '\\') {
                out.append(c);
            } else {                              // a backslash escape such as \n or \"
                char next = json.charAt(++i);
                switch (next) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': break;
                    case 'u': out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); i += 4; break;
                    default:  out.append(next);   // \" \\ \/ become " \ /
                }
            }
        }
        return out.toString();
    }
}
