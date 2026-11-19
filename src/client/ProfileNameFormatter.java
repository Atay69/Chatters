package client;

import java.util.Locale;

public final class ProfileNameFormatter {
    private ProfileNameFormatter() {
    }

    public static String format(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "";
        }
        StringBuilder formatted = new StringBuilder(name.length());
        boolean capitalizeNext = true;
        for (int offset = 0; offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            int category = Character.getType(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                String character = new String(Character.toChars(codePoint));
                formatted.append(capitalizeNext ? character.toUpperCase(Locale.ROOT)
                        : character.toLowerCase(Locale.ROOT));
                capitalizeNext = false;
            } else {
                formatted.appendCodePoint(codePoint);
                if (category != Character.NON_SPACING_MARK
                        && category != Character.COMBINING_SPACING_MARK
                        && category != Character.ENCLOSING_MARK) {
                    capitalizeNext = true;
                }
            }
            offset += Character.charCount(codePoint);
        }
        return formatted.toString();
    }
}
