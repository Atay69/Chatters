package client;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * The coloured emoji set (pictures in src/emoji/{128,44,22}). Swing can only draw emoji text in
 * black and white, so messages swap each known emoji character for its picture, and the picker
 * shows the pictures. Emoji travel through the chat as ordinary Unicode text.
 */
final class Emoji {
    final int codePoint;
    final String name;

    private static final Emoji[] ALL = {
            e(0x1F600, "Grinning"), e(0x1F602, "Tears of joy"), e(0x1F60A, "Smiling"), e(0x1F60D, "Heart eyes"),
            e(0x1F618, "Blowing a kiss"), e(0x1F929, "Star-struck"), e(0x1F60E, "Cool"), e(0x1F973, "Party face"),
            e(0x1F622, "Crying"), e(0x1F62D, "Sobbing"), e(0x1F621, "Angry"), e(0x1F914, "Thinking"),
            e(0x1F634, "Sleeping"), e(0x1F92F, "Mind blown"), e(0x1F609, "Winking"), e(0x1F605, "Nervous laugh"),
            e(0x1F970, "In love"), e(0x1F631, "Screaming"), e(0x1F607, "Angel"), e(0x1F644, "Eye roll"),
            e(0x1F44D, "Thumbs up"), e(0x1F44E, "Thumbs down"), e(0x1F44F, "Clapping"), e(0x1F64F, "Thank you"),
            e(0x1F44B, "Waving"), e(0x1F440, "Eyes"), e(0x2764, "Red heart"), e(0x1F9E1, "Orange heart"),
            e(0x1F49B, "Yellow heart"), e(0x1F49A, "Green heart"), e(0x1F499, "Blue heart"), e(0x1F49C, "Purple heart"),
            e(0x1F5A4, "Black heart"), e(0x1F525, "Fire"), e(0x1F4AF, "One hundred"), e(0x1F389, "Party popper"),
            e(0x1F382, "Birthday cake"), e(0x1F31F, "Glowing star"), e(0x2728, "Sparkles"), e(0x1F308, "Rainbow"),
            e(0x1F319, "Moon"), e(0x1F33B, "Sunflower"), e(0x1F340, "Lucky clover"), e(0x1F680, "Rocket"),
            e(0x1F30D, "Earth"), e(0x1F436, "Dog"), e(0x1F431, "Cat"), e(0x2705, "Done"), e(0x274C, "No")
    };
    private static final Map<Integer, Emoji> BY_CODE = new HashMap<Integer, Emoji>();
    private static final Map<String, Image> IMAGES = new HashMap<String, Image>();

    static {
        for (Emoji emoji : ALL) {
            BY_CODE.put(emoji.codePoint, emoji);
        }
    }

    private Emoji(int codePoint, String name) {
        this.codePoint = codePoint;
        this.name = name;
    }

    private static Emoji e(int codePoint, String name) {
        return new Emoji(codePoint, name);
    }

    static Emoji[] all() {
        return ALL.clone();
    }

    /** The emoji as the Unicode character that is sent in a message. */
    String text() {
        return new String(Character.toChars(codePoint));
    }

    /** The big picture (128 px), for icons; null if the file is missing. */
    Image image() {
        String path = "/emoji/128/" + Integer.toHexString(codePoint) + ".png";
        synchronized (IMAGES) {
            if (!IMAGES.containsKey(path)) {
                Image loaded = null;
                URL url = Emoji.class.getResource(path);
                if (url != null) {
                    try {
                        loaded = ImageIO.read(url);
                    } catch (IOException ignored) {
                        // a missing or damaged picture falls back to the plain character
                    }
                }
                IMAGES.put(path, loaded);
            }
            return IMAGES.get(path);
        }
    }

    /**
     * Replaces every known emoji character in already HTML-escaped text with an {@code <img>} of the
     * given size (22 or 44 px, matching the prepared pictures); other characters are kept.
     */
    static String inline(String escapedText, int px) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < escapedText.length()) {
            int cp = escapedText.codePointAt(i);
            i += Character.charCount(cp);
            URL url = BY_CODE.containsKey(cp)
                    ? Emoji.class.getResource("/emoji/" + px + "/" + Integer.toHexString(cp) + ".png") : null;
            if (url == null) {
                out.appendCodePoint(cp);
                continue;
            }
            out.append("<img src='").append(url).append("' width=").append(px).append(" height=").append(px)
                    .append(" align=middle>");
            if (i < escapedText.length() && escapedText.charAt(i) == '️') {
                i++; // skip the "emoji style" selector that follows e.g. the red heart
            }
        }
        return out.toString();
    }

    static int count(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (BY_CODE.containsKey(cp)) {
                count++;
            }
        }
        return count;
    }

    /** True for a message that is only one to three emoji (shown large, like WhatsApp does). */
    static boolean onlyEmoji(String text) {
        int emoji = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (BY_CODE.containsKey(cp)) {
                emoji++;
            } else if (cp != '️' && !Character.isWhitespace(cp)) {
                return false;
            }
        }
        return emoji >= 1 && emoji <= 3;
    }
}
