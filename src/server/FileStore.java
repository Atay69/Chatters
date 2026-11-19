package server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;

/**
 * Where uploaded files live: one plain file per upload, chatter-data/files/<id>.
 * While a file is still arriving it is called <id>.part, so a half-received file is never offered.
 * The id is a long random number; only people who got the message with it can ask for the file.
 */
final class FileStore {
    private static final Path DIRECTORY = Paths.get(System.getProperty("chat.dataDir", "chatter-data"), "files");
    private static final SecureRandom RANDOM = new SecureRandom();

    private FileStore() {
    }

    static long newId() {
        return RANDOM.nextLong() & Long.MAX_VALUE;
    }

    /** The finished file. */
    static Path path(long id) {
        return DIRECTORY.resolve(Long.toString(id));
    }

    /** The file while it is being received (the folder is created when needed). */
    static Path partPath(long id) throws IOException {
        Files.createDirectories(DIRECTORY);
        return DIRECTORY.resolve(id + ".part");
    }

    /** A file name that is safe to show: no folders, no control characters, not too long. */
    static String cleanName(String name) {
        String clean = name.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty()) {
            clean = "file";
        }
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }
}
