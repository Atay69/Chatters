package client;

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
    final byte[] data;

    ChatEntry(String sender, String timestamp, String text, byte type, byte[] data) {
        this.sender = sender;
        this.timestamp = timestamp;
        this.text = text;
        this.type = type;
        this.data = data;
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
        return bytes < 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
                : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String withoutBrackets() {
        String value = timestamp == null ? "" : timestamp.trim();
        return value.startsWith("(") && value.endsWith(")") ? value.substring(1, value.length() - 1) : value;
    }
}
