package server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Accounts: a name and a password (stored only as a salted PBKDF2WithHmacSHA256 hash)
 * in a small binary file, in the same style as the rest of chatter-data.
 */
public final class UserStore {
    private static final int ITERATIONS = 120_000;
    private static final int MAX_NAME_LENGTH = 32;

    private final Path file;
    private final SecureRandom random = new SecureRandom();
    /** lower-case name -> { display name, salt (hex), hash (hex) } */
    private final Map<String, String[]> accounts = new HashMap<String, String[]>();

    public UserStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file.toFile())))) {
                while (true) {
                    String[] record = { in.readUTF(), in.readUTF(), in.readUTF() };
                    accounts.put(key(record[0]), record);
                }
            } catch (EOFException endOfFile) {
                // normal end of the account list
            }
        }
    }

    /** The registered account names, spelled as they were registered. */
    public synchronized java.util.List<String> names() {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (String[] record : accounts.values()) {
            names.add(record[0]);
        }
        return names;
    }

    /** @return null on success, otherwise a message that can be shown to the user. */
    public synchronized String register(String name, String password) {
        String display = name == null ? "" : name.trim();
        if (display.isEmpty() || display.length() > MAX_NAME_LENGTH || !display.matches("[^\\p{Cntrl}]+")) {
            return "Choose an account name of 1 to " + MAX_NAME_LENGTH + " characters.";
        }
        if (password == null || password.length() < 3) {
            return "Your password needs at least 3 characters.";
        }
        if (accounts.containsKey(key(display))) {
            return "That account name is already taken.";
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String[] record = { display, HexFormat.of().formatHex(salt), hash(password, salt) };
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file.toFile(), true)))) {
            out.writeUTF(record[0]);
            out.writeUTF(record[1]);
            out.writeUTF(record[2]);
        } catch (IOException error) {
            return "Could not save your account: " + error.getMessage();
        }
        accounts.put(key(display), record);
        return null;
    }

    /** @return the account name exactly as it was registered, or null when the login is wrong. */
    public synchronized String authenticate(String name, String password) {
        String[] record = name == null || password == null ? null : accounts.get(key(name.trim()));
        if (record == null) {
            return null;
        }
        byte[] expected = HexFormat.of().parseHex(record[2]);
        byte[] actual = HexFormat.of().parseHex(hash(password, HexFormat.of().parseHex(record[1])));
        return MessageDigest.isEqual(expected, actual) ? record[0] : null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String hash(String password, byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] bytes = factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).getEncoded();
            return HexFormat.of().formatHex(bytes);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password hashing is unavailable.", error);
        }
    }
}
