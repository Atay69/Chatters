package client;

import shared.ChatProtocol;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicProgressBarUI;
import javax.sound.sampled.LineUnavailableException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;

/**
 * The bottom bar: tool buttons (file, image, emoji, voice, AI), the message box, and the Send button.
 * Above the box it can show AI reply suggestions, an upload progress bar and a status line.
 *
 * Enter sends the message, Shift+Enter starts a new line. With an AI assistant on the server,
 * Tab asks it to finish your sentence.
 */
final class Composer extends Widgets.TintedPanel {
    private static final int MAX_TEXT_CHARS = 60000;

    /** What the composer needs from the window around it. */
    interface Host {
        ChatClient client();

        /** The person of the open private chat, or null when GroupChat is open. */
        String activePeer();

        Frame frame();

        void connectionLost(IOException error);

        /** Adds a local system line (not sent to anyone) to the open conversation. */
        void notice(String text);

        /** Sends a message to the "AI Assistant" conversation (shows it and asks the AI). */
        void askAi(String text) throws IOException;
    }

    private final Host host;
    private final VoiceRecorder recorder = new VoiceRecorder();

    private final InputArea input = new InputArea();
    private final Widgets.TintedPanel bar = new Widgets.TintedPanel(new BorderLayout(8, 0), Theme.Role.INPUT, 50);
    private final Widgets.Button attachButton = iconButton(LineIcon.Kind.FILE, "Send a file");
    private final Widgets.Button imageButton = iconButton(LineIcon.Kind.IMAGE, "Send an image");
    private final Widgets.Button emojiButton = iconButton(LineIcon.Kind.EMOJI, "Insert an emoji");
    private final Widgets.Button recordButton = iconButton(LineIcon.Kind.MICROPHONE, "Record a voice message");
    private final Widgets.Button aiButton = iconButton(LineIcon.Kind.SPARKLE,
            "Send the text in the box to the AI assistant (or write /ai and your question)");
    private final Widgets.Button sendButton = new Widgets.Button("Send", null, null);
    private final JPanel suggestions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JProgressBar uploadBar = new JProgressBar(0, 100);
    private final JPanel uploadRow = new JPanel(new BorderLayout(8, 0));
    private final Widgets.Button cancelButton = new Widgets.Button("Cancel", Theme.Role.CONTROL, Theme.Role.TEXT);
    private ChatClient.Upload currentUpload; // the running upload (null = none); the Cancel button stops it
    private final Widgets.Label status = new Widgets.Label(" ", Theme.Role.MUTED);
    private Timer hideBarTimer;
    private boolean uploading;

    Composer(Host host) {
        super(new BorderLayout(), Theme.Role.COMPOSER);
        this.host = host;
        edges("T");
        setBorder(BorderFactory.createEmptyBorder(12, 24, 20, 24));

        // above the bar: AI suggestions, upload progress, status line
        suggestions.setOpaque(false);
        suggestions.setVisible(false);
        uploadBar.setUI(new UploadBarUI());
        uploadBar.setStringPainted(true);
        uploadBar.setFont(Theme.font(Font.BOLD, 11));
        uploadBar.setPreferredSize(new Dimension(100, 20));
        uploadRow.setOpaque(false);
        uploadRow.setBorder(BorderFactory.createEmptyBorder(0, 5, 6, 5));
        uploadRow.add(uploadBar, BorderLayout.CENTER);
        cancelButton.setToolTipText("Stop this upload");
        cancelButton.addActionListener(event -> {
            if (currentUpload != null) {
                currentUpload.cancel();
                cancelButton.setEnabled(false);
            }
        });
        uploadRow.add(cancelButton, BorderLayout.EAST);
        uploadRow.setVisible(false);
        status.setFont(Theme.font(Font.PLAIN, 11));
        status.setBorder(BorderFactory.createEmptyBorder(0, 5, 6, 0));
        JPanel above = new JPanel(new BorderLayout());
        above.setOpaque(false);
        above.add(suggestions, BorderLayout.NORTH);
        above.add(uploadRow, BorderLayout.CENTER);
        above.add(status, BorderLayout.SOUTH);
        add(above, BorderLayout.NORTH);

        // the bar itself: tools | message box | Send
        JPanel tools = new JPanel();
        tools.setOpaque(false);
        tools.setLayout(new BoxLayout(tools, BoxLayout.X_AXIS));
        for (Widgets.Button tool : new Widgets.Button[] { attachButton, imageButton, emojiButton, recordButton, aiButton }) {
            tools.add(tool);
            tools.add(Box.createHorizontalStrut(4));
        }
        sendButton.setFont(Theme.font(Font.BOLD, 14));
        sendButton.setForeground(Color.WHITE);
        updateAccent();
        sendButton.setCorner(50);
        sendButton.setPreferredSize(new Dimension(104, 50));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 18, 4, 10));
        bar.add(Widgets.scrollPane(input), BorderLayout.CENTER);
        bar.add(tools, BorderLayout.EAST);
        JPanel barRow = new JPanel(new BorderLayout(10, 0)); // [ message box with tools ] [ Send ]
        barRow.setOpaque(false);
        barRow.add(bar, BorderLayout.CENTER);
        barRow.add(sendButton, BorderLayout.EAST);
        add(barRow, BorderLayout.CENTER);

        setupInput();
        attachButton.addActionListener(event -> chooseFile(false));
        imageButton.addActionListener(event -> chooseFile(true));
        emojiButton.addActionListener(event -> EmojiPicker.show(emojiButton, input));
        recordButton.addActionListener(event -> toggleRecording());
        aiButton.addActionListener(event -> askAssistant());
        sendButton.addActionListener(event -> sendText());
    }

    // ---------------------------------------------------------------- what the window can ask for

    void focusInput() {
        input.requestFocusInWindow();
    }

    /** Adds text at the end of the message box (an AI completion). */
    void append(String text) {
        input.append(text);
    }

    void showStatus(String text, boolean error) {
        status.setText(text);
        status.setRole(error ? Theme.Role.ERROR : Theme.Role.MUTED);
    }

    /** The AI conversation takes text only: no files, images or voice messages there. */
    void setTextOnly(boolean textOnly) {
        for (Widgets.Button tool : new Widgets.Button[] { attachButton, imageButton, recordButton }) {
            tool.setEnabled(!textOnly);
        }
    }

    /** After the connection is lost: nothing can be sent any more. */
    void disableAll() {
        setBusy(true);
    }

    void showSuggestions(String[] replies) {
        suggestions.removeAll();
        for (int i = 0; i < Math.min(3, replies.length); i++) {
            String reply = replies[i].trim();
            if (reply.isEmpty()) {
                continue;
            }
            Widgets.Button chip = new Widgets.Button(reply, Theme.Role.CONTROL, Theme.Role.TEXT);
            chip.setToolTipText("Use this reply");
            chip.addActionListener(event -> {
                input.setText(reply);
                input.requestFocusInWindow();
                clearSuggestions();
            });
            suggestions.add(chip);
        }
        suggestions.setVisible(suggestions.getComponentCount() > 0);
        suggestions.revalidate();
    }

    void clearSuggestions() {
        suggestions.removeAll();
        suggestions.setVisible(false);
        suggestions.revalidate();
    }

    // ---------------------------------------------------------------- sending text

    private void sendText() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_TEXT_CHARS) {
            JOptionPane.showMessageDialog(host.frame(), "Messages must be shorter than 60,000 characters.");
            return;
        }
        try {
            if (editing != null) { // "Save": the text replaces the message that is being edited
                host.client().editMessage(host.activePeer(), editing.id, text);
                finishEditing();
            } else if (ChatProtocol.AI_NAME.equals(host.activePeer())) {
                host.askAi(text); // the AI conversation lives on the client; the server only answers
            } else {
                host.client().sendText(text, host.activePeer());
            }
            input.setText("");
            clearSuggestions();
        } catch (IOException error) {
            host.connectionLost(error);
        }
    }

    // ---------------------------------------------------------------- files and images

    private void chooseFile(boolean imageOnly) {
        File file = imageOnly ? NativeFileDialog.openPicture(host.frame(), "Choose an image to send")
                : NativeFileDialog.open(host.frame(), "Choose a file to send", "All files");
        if (file != null) {
            upload(imageOnly ? ChatProtocol.TYPE_IMAGE : ChatProtocol.TYPE_FILE, file);
        }
    }

    /** Sends a file in the background and shows the progress bar while it uploads. */
    private void upload(byte type, File file) {
        if (uploading) {
            showStatus("Wait for the current file transfer to finish.", false);
            return;
        }
        uploading = true;
        cancelButton.setEnabled(true);
        setBusy(true);
        showStatus("Sending " + file.getName() + " (0%)...", false);
        showProgress(file.getName(), 0, "0%");
        try {
            currentUpload = host.client().sendFile(type, file, host.activePeer(), new ChatClient.TransferListener() {
                @Override
                public void onProgress(long sent, long total) {
                    int percent = total == 0 ? 100 : (int) (sent * 100 / total);
                    showStatus("Sending " + file.getName() + " (" + percent + "%)...", false);
                    showProgress(file.getName(), percent, percent + "% of " + ChatEntry.formatSize(total));
                }

                @Override
                public void onComplete() {
                    uploading = false;
                    finishProgress(file.getName(), true);
                    showStatus("Uploaded " + file.getName() + "; waiting for server confirmation...", false);
                    setBusy(false);
                }

                @Override
                public void onCancelled() {
                    uploading = false;
                    uploadRow.setVisible(false);
                    showStatus("Upload cancelled: " + file.getName(), false);
                    setBusy(false);
                }

                @Override
                public void onFailure(IOException error) {
                    uploading = false;
                    finishProgress(file.getName(), false);
                    showStatus("Could not send " + file.getName() + ": " + error.getMessage(), true);
                    host.connectionLost(error);
                }
            });
        } catch (IOException rejected) { // unreadable, or too big: nothing was sent
            uploading = false;
            uploadRow.setVisible(false);
            setBusy(false);
            showStatus("Could not send " + file.getName() + ": " + rejected.getMessage(), true);
        }
    }

    private void showProgress(String fileName, int percent, String label) {
        if (hideBarTimer != null) {
            hideBarTimer.stop();
        }
        uploadBar.setValue(percent);
        uploadBar.setString(fileName + " — " + label);
        uploadRow.setVisible(true);
    }

    /** Leaves the finished bar on screen for a moment, then hides it. */
    private void finishProgress(String fileName, boolean success) {
        showProgress(fileName, success ? 100 : uploadBar.getValue(), success ? "uploaded" : "failed");
        hideBarTimer = new Timer(success ? 1500 : 4000, event -> uploadRow.setVisible(false));
        hideBarTimer.setRepeats(false);
        hideBarTimer.start();
    }

    /** Disables or enables everything that sends something. */
    private void setBusy(boolean busy) {
        for (JComponent part : new JComponent[] { attachButton, imageButton, recordButton, sendButton, input }) {
            part.setEnabled(!busy);
        }
    }

    // ---------------------------------------------------------------- voice messages

    /** Records (first click) and sends (second click) a voice message to the open conversation. */
    private void toggleRecording() {
        try {
            if (!recorder.isRecording()) {
                recorder.start();
                recordButton.setIcon(null);
                recordButton.setText("■");
                recordButton.setTextRole(null); // plain white text on red while recording
                recordButton.setForeground(Color.WHITE);
                recordButton.setBackgroundOverride(new Color(218, 91, 103));
                return;
            }
            byte[] wav = recorder.stop();
            recordButton.setText("");
            recordButton.setIcon(new LineIcon(LineIcon.Kind.MICROPHONE, 24));
            recordButton.setTextRole(Theme.Role.TEXT);
            recordButton.setBackgroundOverride(null);
            String peer = host.activePeer(); // null = GroupChat
            host.client().sendBytes(ChatProtocol.TYPE_AUDIO, "voice_message.wav", wav, peer, null);
        } catch (LineUnavailableException error) {
            JOptionPane.showMessageDialog(host.frame(), "Microphone unavailable: " + error.getMessage());
        } catch (IOException error) {
            host.connectionLost(error);
        }
    }

    // ---------------------------------------------------------------- AI assistant

    private void sendAiQuery(char kind, String text) {
        try {
            String peer = host.activePeer();
            host.client().sendAiQuery(kind, peer == null ? "" : peer, text);
        } catch (IOException error) {
            host.connectionLost(error);
        }
    }

    /** Sparkle button: sends the text in the message box to the assistant as a question. */
    private void askAssistant() {
        String question = input.getText().trim();
        if (question.isEmpty()) {
            showStatus("Type a question first, then press the AI button (or write /ai and your question).", false);
            return;
        }
        host.notice("You asked the AI: " + question);
        sendAiQuery(ChatProtocol.AI_ASK, question);
        input.setText("");
    }

    /** Tab key: asks the assistant to continue the sentence being typed. */
    private void completeWithAi() {
        if (!input.getText().trim().isEmpty()) {
            sendAiQuery(ChatProtocol.AI_COMPLETE, input.getText());
        }
    }

    /** Gives the Send button the user's chosen accent colour (the outline and progress bar read it themselves). */
    void updateAccent() {
        sendButton.setBackgroundOverride(Theme.accent());
    }

    /** Asks for reply suggestions (the window calls this after a new message from someone else). */
    void requestSuggestions() {
        sendAiQuery(ChatProtocol.AI_SUGGEST, "");
    }

    // ---------------------------------------------------------------- editing one of my messages

    private ChatEntry editing; // the message being edited (null = a normal new message)

    /** "Edit message": puts its text in the box; Enter / Send now saves the change, Esc cancels. */
    void startEditing(ChatEntry entry) {
        editing = entry;
        input.setText(entry.text);
        input.requestFocusInWindow();
        sendButton.setText("Save");
        showStatus("Editing your message: press Enter or Save to keep the change, Esc to cancel.", false);
    }

    /** Leaves edit mode without saving (Esc, or you opened another conversation). */
    void cancelEditing() {
        if (editing != null) {
            finishEditing();
            input.setText("");
        }
    }

    private void finishEditing() {
        editing = null;
        sendButton.setText("Send");
        showStatus(" ", false);
    }

    // ---------------------------------------------------------------- the message box

    private void setupInput() {
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setFont(Theme.font(Font.PLAIN, 13));
        input.setBorder(BorderFactory.createEmptyBorder(9, 4, 9, 6));
        input.getAccessibleContext().setAccessibleName("Message");
        input.addFocusListener(new FocusAdapter() { // the bar's outline turns accent-coloured while typing
            @Override public void focusGained(FocusEvent event) { bar.setHighlighted(true); }
            @Override public void focusLost(FocusEvent event) { bar.setHighlighted(false); }
        });
        // keys: Esc cancels an edit, Enter sends, Shift+Enter inserts a new line, Tab asks the AI to finish the sentence
        bind(KeyEvent.VK_ESCAPE, 0, this::cancelEditing);
        bind(KeyEvent.VK_ENTER, 0, this::sendText);
        bind(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK, () -> input.replaceSelection("\n"));
        if (host.client().aiAvailable()) {
            input.setFocusTraversalKeysEnabled(false); // so that Tab is free for the assistant
            bind(KeyEvent.VK_TAB, 0, this::completeWithAi);
        }
        // the box grows with the text, up to 4 lines
        Runnable resize = () -> {
            input.setRows(Math.min(4, Math.max(1, input.getLineCount())));
            input.revalidate();
        };
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { resize.run(); }
            @Override public void removeUpdate(DocumentEvent event) { resize.run(); }
            @Override public void changedUpdate(DocumentEvent event) { resize.run(); }
        });
    }

    /** Makes a key press in the message box run {@code action}. */
    private void bind(int key, int modifiers, Runnable action) {
        String name = key + "/" + modifiers; // any unique name will do
        input.getInputMap().put(KeyStroke.getKeyStroke(key, modifiers), name);
        input.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    /** The message box: transparent (the bar behind it is solid) and coloured by the theme. */
    private static final class InputArea extends JTextArea {
        InputArea() {
            super(1, 24);
            setOpaque(false);
        }

        @Override
        public Color getForeground() {
            return Theme.Role.TEXT.color();
        }

        @Override
        public Color getCaretColor() {
            return Theme.Role.TEXT.color();
        }
    }

    private static Widgets.Button iconButton(LineIcon.Kind icon, String tooltip) {
        Widgets.Button button = new Widgets.Button("", null, Theme.Role.TEXT);
        button.setIcon(new LineIcon(icon, 24));
        button.setToolTipText(tooltip);
        button.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        button.setPreferredSize(new Dimension(38, 38));
        return button;
    }

    /** The upload progress bar: a rounded track with an accent-coloured fill and the text in the middle. */
    private static final class UploadBarUI extends BasicProgressBarUI {
        @Override
        protected void paintDeterminate(Graphics graphics, JComponent component) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = progressBar.getWidth();
                int height = progressBar.getHeight();
                g.setColor(Theme.Role.CONTROL.color());
                g.fillRoundRect(0, 0, width, height, height, height);
                int filled = (int) Math.round(width * progressBar.getPercentComplete());
                if (filled > 0) {
                    g.setColor(Theme.accent());
                    g.fillRoundRect(0, 0, Math.max(filled, height), height, height, height);
                }
                g.setFont(progressBar.getFont());
                g.setColor(Theme.Role.TEXT.color());
                String text = progressBar.getString();
                g.drawString(text, (width - g.getFontMetrics().stringWidth(text)) / 2,
                        (height - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent());
            } finally {
                g.dispose();
            }
        }
    }

}
