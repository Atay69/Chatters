package client;

import shared.ChatProtocol;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * One message in a conversation: who sent it, when, what it says, and (for images, files and voice
 * messages) the attached bytes. {@code type} is one of the ChatProtocol.TYPE_* values.
 */
final class ChatEntry {
    final String sender;
    final String timestamp; // as sent by the server, e.g. "(2026-11-18T17:40:12)"
    final String text;      // the message text, or the file name for attachments
    final byte type;
    final byte[] data;      // the bytes of an old (saved inside the history) attachment; empty for stored files
    final long fileId;      // a file stored on the server: its id (-1 = this message is not a stored file)
    final long fileSize;    // the size of the attachment in bytes

    ChatEntry(String sender, String timestamp, String text, byte type, byte[] data) {
        this.sender = sender;
        this.timestamp = timestamp;
        // A text message that starts with FILE_MARK is really a stored file: "id|kind|size|name".
        String[] file = (type == ChatProtocol.TYPE_TEXT || type == ChatProtocol.TYPE_PRIVATE) && text.startsWith(ChatProtocol.FILE_MARK)
                ? text.substring(ChatProtocol.FILE_MARK.length()).split("\\|", 4) : null;
        long id = -1;
        long size = data.length;
        byte kind = type;
        String name = text;
        if (file != null && file.length == 4) {
            try {
                id = Long.parseLong(file[0]);
                kind = Byte.parseByte(file[1]);
                size = Long.parseLong(file[2]);
                name = file[3];
                data = new byte[0];
            } catch (NumberFormatException damaged) {
                id = -1; // not a file reference after all: show it as text
            }
        }
        this.fileId = id;
        this.fileSize = size;
        this.type = id >= 0 ? kind : type;
        this.text = id >= 0 ? name : text;
        this.data = data;
    }

    /** True when the file lives on the server and has to be downloaded to be shown or saved. */
    boolean stored() {
        return fileId >= 0;
    }

    /** The day the message was sent (older saved messages without a date count as today). */
    LocalDate date() {
        try {
            return LocalDateTime.parse(withoutBrackets(), DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException noDate) {
            return LocalDate.now();
        }
    }

    /** The time of day as "HH:mm". */
    String time() {
        String value = withoutBrackets();
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException noDate) {
            try {
                return LocalTime.parse(value, DateTimeFormatter.ofPattern("H:mm[:ss]"))
                        .format(DateTimeFormatter.ofPattern("HH:mm"));
            } catch (DateTimeParseException unknown) {
                return timestamp;
            }
        }
    }

    /** File sizes for people: "812 B", "40.5 KB", "38.0 MB". */
    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes < 1024L * 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
                : String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String withoutBrackets() {
        String value = timestamp == null ? "" : timestamp.trim();
        return value.startsWith("(") && value.endsWith(")") ? value.substring(1, value.length() - 1) : value;
    }
}
