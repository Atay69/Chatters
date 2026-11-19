package server;

public final class ProfanityFilter {
    private ProfanityFilter() {
    }

    /**
     * Placeholder hook. Replace with a reviewed moderation policy before relying on
     * automated filtering; this intentionally preserves input until then.
     */
    public static String filter(String text) {
        return text;
    }
}
