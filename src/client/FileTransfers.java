package client;

import javax.swing.JOptionPane;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Everything that brings a file stored on the server to this computer:
 *   fetch()    loads a whole file into memory (to show a picture or play a voice message)
 *   download() is the Download button: the user chooses where to save, the file streams to disk
 * (Uploading is in Composer; both use ChatClient, which does the chunked transfer.)
 */
final class FileTransfers {
    /** Where progress and errors are shown (the status line above the message box). */
    interface Status {
        void show(String text, boolean error);
    }

    private static final int MAX_CACHED_BYTES = 16 * 1024 * 1024;

    private final Composer.Host host;
    private final Status status;
    private final Map<Long, byte[]> cache = new HashMap<Long, byte[]>(); // small files, so redrawing a chat does not download them again

    FileTransfers(Composer.Host host, Status status) {
        this.host = host;
        this.status = status;
    }

    /** Downloads a whole file into memory; {@code done} gets the bytes. */
    void fetch(ChatEntry entry, Consumer<byte[]> done) {
        byte[] cached = cache.get(entry.fileId);
        if (cached != null) {
            done.accept(cached);
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            host.client().download(entry.fileId, entry.fileSize, buffer, new ChatClient.TransferListener() {
                @Override
                public void onProgress(long received, long total) {
                }

                @Override
                public void onComplete() {
                    byte[] bytes = buffer.toByteArray();
                    if (bytes.length <= MAX_CACHED_BYTES) {
                        cache.put(entry.fileId, bytes);
                    }
                    done.accept(bytes);
                }

                @Override
                public void onFailure(IOException error) {
                    status.show("Could not load " + entry.text + ".", true);
                }
            });
        } catch (IOException error) {
            host.connectionLost(error);
        }
    }

    /** "Download": asks where to save the file, then streams it there while the chat keeps working. */
    void download(ChatEntry entry) {
        File target = NativeFileDialog.save(host.frame(), "Save shared file", entry.text);
        if (target == null) {
            return; // cancelled
        }
        try {
            host.client().download(entry.fileId, entry.fileSize,
                    new BufferedOutputStream(new FileOutputStream(target), 1024 * 1024),
                    new ChatClient.TransferListener() {
                        @Override
                        public void onProgress(long received, long total) {
                            int percent = total == 0 ? 100 : (int) (received * 100 / total);
                            status.show("Downloading " + entry.text + " (" + percent + "%)...", false);
                        }

                        @Override
                        public void onComplete() {
                            status.show("Saved " + entry.text + " to " + target.getParent(), false);
                        }

                        @Override
                        public void onFailure(IOException error) {
                            status.show("Could not download " + entry.text + ": " + error.getMessage(), true);
                        }
                    });
        } catch (IOException error) {
            JOptionPane.showMessageDialog(host.frame(), "Could not save the file: " + error.getMessage(),
                    "Download failed", JOptionPane.ERROR_MESSAGE);
        }
    }
}
