package client;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public final class RememberedProfileStore {
    private static final String NAME_KEY = "lastSuccessfulDisplayName";
    private final Preferences preferences;

    public RememberedProfileStore(Preferences preferences) {
        this.preferences = preferences;
    }

    public String load() {
        String name = requirePreferences().get(NAME_KEY, null);
        return name == null || name.trim().isEmpty() || name.length() > 32 ? null : name;
    }

    public void rememberAfterSuccessfulConnection(String displayName) throws BackingStoreException {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("A non-blank display name is required.");
        }
        Preferences store = requirePreferences();
        store.put(NAME_KEY, displayName);
        store.flush();
    }

    public void forget() throws BackingStoreException {
        Preferences store = requirePreferences();
        store.remove(NAME_KEY);
        store.flush();
    }

    private Preferences requirePreferences() {
        if (preferences == null) {
            throw new IllegalStateException("Local profile storage is unavailable.");
        }
        return preferences;
    }
}
