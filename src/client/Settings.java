package client;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** Small settings kept on this computer (theme, wallpaper, remembered account name). Never throws. */
final class Settings {
    private static final Preferences PREFERENCES = open();

    private Settings() {
    }

    private static Preferences open() {
        try {
            return Preferences.userRoot().node("/<unnamed>");
        } catch (RuntimeException unavailable) {
            System.err.println("Local settings are unavailable: " + unavailable.getMessage());
            return null;
        }
    }

    /** The raw store (the remembered-name feature needs it); null when settings are unavailable. */
    static Preferences store() {
        return PREFERENCES;
    }

    static boolean getBoolean(String key, boolean fallback) {
        try {
            return PREFERENCES == null ? fallback : PREFERENCES.getBoolean(key, fallback);
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    static int getInt(String key, int fallback) {
        try {
            return PREFERENCES == null ? fallback : PREFERENCES.getInt(key, fallback);
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    static void putBoolean(String key, boolean value) {
        if (PREFERENCES != null) {
            PREFERENCES.putBoolean(key, value);
            flush();
        }
    }

    static void putInt(String key, int value) {
        if (PREFERENCES != null) {
            PREFERENCES.putInt(key, value);
            flush();
        }
    }

    private static void flush() {
        try {
            PREFERENCES.flush();
        } catch (BackingStoreException | RuntimeException error) {
            System.err.println("Could not save a local setting: " + error.getMessage());
        }
    }
}
