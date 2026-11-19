package client;

import javax.swing.SwingUtilities;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The "choose a file" and "save as" windows of the program.
 *
 * On Windows they open the real, modern Windows window (the same "Open" window Discord, Chrome and
 * Word use). Java's own FileDialog is the old Windows 7 look, so we ask Windows itself through a tiny
 * PowerShell script. On other systems, or if that fails, Java's FileDialog is used instead.
 *
 * Both methods wait for the answer like a normal dialog (the program keeps repainting meanwhile) and
 * return the chosen file, or null when the user cancelled.
 */
final class NativeFileDialog {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** The PowerShell script. The texts for the window come in through environment variables (never pasted into the script). */
    private static final String SCRIPT = "Add-Type -AssemblyName System.Windows.Forms; "
            + "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
            + "$owner = New-Object System.Windows.Forms.Form -Property @{ TopMost = $true }; "
            + "if ($env:CHATTERS_SAVE -eq '1') { $d = New-Object System.Windows.Forms.SaveFileDialog } "
            + "else { $d = New-Object System.Windows.Forms.OpenFileDialog }; "
            + "$d.Title = $env:CHATTERS_TITLE; "
            + "$d.Filter = $env:CHATTERS_FILTER; "
            + "if ($env:CHATTERS_FILE) { $d.FileName = $env:CHATTERS_FILE }; "
            + "if ($d.ShowDialog($owner) -eq 'OK') { [Console]::Out.Write($d.FileName); exit 0 } else { exit 1 }";

    private NativeFileDialog() {
    }

    /**
     * Asks for a file to open. {@code description} and {@code extensions} limit what is listed, for example
     * ("Pictures", "png", "jpg"); with no extensions every file is listed.
     */
    static File open(Frame owner, String title, String description, String... extensions) {
        return ask(false, owner, title, "", description, extensions);
    }

    /** Asks for a picture (PNG, JPG, GIF or BMP). */
    static File openPicture(Frame owner, String title) {
        return open(owner, title, "Pictures", "png", "jpg", "jpeg", "gif", "bmp");
    }

    /** Asks where to save a file; {@code suggestedName} is the file name that is already filled in. */
    static File save(Frame owner, String title, String suggestedName) {
        return ask(true, owner, title, suggestedName, "All files");
    }

    private static File ask(boolean save, Frame owner, String title, String suggestedName, String description, String... extensions) {
        if (WINDOWS) {
            try {
                return askWindows(save, title, suggestedName, windowsFilter(description, extensions));
            } catch (IOException | InterruptedException | RuntimeException unavailable) {
                System.err.println("The Windows file window could not be opened (" + unavailable.getMessage() + "); using Java's.");
            }
        }
        return askJava(save, owner, title, suggestedName, extensions);
    }

    // ---------------------------------------------------------------- Windows

    /** "Pictures|*.png;*.jpg|All files|*.*" */
    private static String windowsFilter(String description, String[] extensions) {
        StringBuilder filter = new StringBuilder();
        if (extensions.length > 0) {
            filter.append(description).append('|');
            for (int i = 0; i < extensions.length; i++) {
                filter.append(i == 0 ? "" : ";").append("*.").append(extensions[i]);
            }
            filter.append('|');
        }
        return filter.append("All files|*.*").toString();
    }

    private static File askWindows(boolean save, String title, String suggestedName, String filter) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-STA",
                "-ExecutionPolicy", "Bypass", "-Command", SCRIPT);
        builder.environment().put("CHATTERS_SAVE", save ? "1" : "0");
        builder.environment().put("CHATTERS_TITLE", title);
        builder.environment().put("CHATTERS_FILTER", filter);
        builder.environment().put("CHATTERS_FILE", suggestedName);
        builder.redirectErrorStream(true); // nothing is left unread in a pipe that could block the script
        Process process = builder.start();

        // Wait for the answer without freezing the program: a secondary event loop keeps the window repainting.
        String[] answer = { null };
        int[] exit = { -1 };
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        Thread waiter = new Thread(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = out.readLine(); line != null; line = out.readLine()) {
                    if (!line.trim().isEmpty()) {
                        answer[0] = line.trim(); // the chosen path is the last line the script prints
                    }
                }
                exit[0] = process.waitFor();
            } catch (IOException | InterruptedException error) {
                exit[0] = -1;
            }
            SwingUtilities.invokeLater(loop::exit); // queued, so it also works if we finish before the loop has started
        }, "file-window");
        waiter.setDaemon(true);
        waiter.start();
        if (EventQueue.isDispatchThread()) {
            loop.enter();
        } else {
            waiter.join();
        }

        if (exit[0] == 1) {
            return null; // the user pressed Cancel
        }
        if (exit[0] != 0 || answer[0] == null || answer[0].isEmpty()) {
            throw new IOException("PowerShell answered with code " + exit[0]);
        }
        return new File(answer[0]);
    }

    // ---------------------------------------------------------------- everything else (and fallback)

    private static File askJava(boolean save, Frame owner, String title, String suggestedName, String[] extensions) {
        FileDialog dialog = new FileDialog(owner, title, save ? FileDialog.SAVE : FileDialog.LOAD);
        if (save) {
            dialog.setFile(suggestedName);
        } else if (extensions.length > 0) {
            String pattern = ".*\\.(" + String.join("|", extensions) + ")";
            dialog.setFilenameFilter((directory, name) -> name.toLowerCase(Locale.ROOT).matches(pattern));
        }
        dialog.setVisible(true);
        return dialog.getFile() == null ? null : new File(dialog.getDirectory(), dialog.getFile());
    }
}
