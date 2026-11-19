package client;

import java.util.prefs.Preferences;

public final class ProfileExperienceTest {
    public static void main(String[] args) throws Exception {
        testFormatting();
        testRememberedProfileLifecycle();
        testStorageFailure();
        System.out.println("Profile experience checks passed.");
    }

    private static void testFormatting() {
        assertEquals("Jane Doe", ProfileNameFormatter.format("jANE DOE"));
        assertEquals("  Mary   Jane ", ProfileNameFormatter.format("  mARY   jANE "));
        assertEquals("O'Neil-Smith", ProfileNameFormatter.format("o'NEIL-smITH"));
        assertEquals("Élodie 李", ProfileNameFormatter.format("ÉLODIE 李"));
        assertEquals("", ProfileNameFormatter.format(null));
        assertEquals("", ProfileNameFormatter.format(" \t "));
    }

    private static void testRememberedProfileLifecycle() throws Exception {
        Preferences preferences = Preferences.userRoot().node(
                "/chatter-tests/profile-" + System.nanoTime());
        try {
            RememberedProfileStore store = new RememberedProfileStore(preferences);
            assertEquals(null, store.load());
            store.rememberAfterSuccessfulConnection("jANE DOE");
            assertEquals("jANE DOE", store.load());
            assertEquals("Jane Doe", ProfileNameFormatter.format(store.load()));

            // A failed/offline connection does not overwrite the last server-confirmed name.
            assertEquals("jANE DOE", store.load());
            store.rememberAfterSuccessfulConnection("mARY jONES");
            assertEquals("mARY jONES", store.load());

            store.forget();
            assertEquals(null, store.load());
        } finally {
            Preferences parent = preferences.parent();
            preferences.removeNode();
            parent.flush();
        }
    }

    private static void testStorageFailure() throws Exception {
        Preferences preferences = Preferences.userRoot().node(
                "/chatter-tests/removed-" + System.nanoTime());
        RememberedProfileStore store = new RememberedProfileStore(preferences);
        preferences.removeNode();
        try {
            store.load();
            throw new AssertionError("Expected removed preference node to report storage failure.");
        } catch (IllegalStateException expected) {
            // The UI reports this failure and continues without the offline profile.
        }
        try {
            new RememberedProfileStore(null).load();
            throw new AssertionError("Expected unavailable preferences to report a storage failure.");
        } catch (IllegalStateException expected) {
            // An inaccessible preference root is handled the same way.
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
