package client;

import shared.AppInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.net.InetAddress;

/** The sign-in window: account name + password, "Create account" mode, server address. */
final class LoginDialog {
    private static final int PORT = 5000;
    private static final Color BACKGROUND = new Color(22, 22, 25);
    private static final Color FIELD = new Color(46, 46, 50);
    private static final Color ACCENT = new Color(240, 240, 243); // the app name and the links: white
    private static final Color MUTED = new Color(150, 150, 158);

    /** A signed-in connection plus whether "Remember me" was ticked. */
    static final class Result {
        final ChatClient client;
        final boolean remember;

        private Result(ChatClient client, boolean remember) {
            this.client = client;
            this.remember = remember;
        }
    }

    private final JDialog dialog = new JDialog((JFrame) null, AppInfo.NAME + " — Sign in", true);
    private final JLabel title = new JLabel("SIGN IN", SwingConstants.LEFT);
    private final JTextField nameField;
    private final JPasswordField passwordField = new JPasswordField();
    private final JTextField hostField;
    private final JCheckBox remember = new JCheckBox("Remember my account name");
    private final JLabel status = new JLabel(" ");
    private final Widgets.Button connect = new Widgets.Button("Sign in", null, null);
    private final JButton switchMode = new JButton();
    private boolean registerMode;
    private Result result;

    private LoginDialog(String rememberedName, String host) {
        nameField = field(new JTextField(rememberedName == null ? System.getProperty("user.name", "Guest") : rememberedName));
        hostField = field(new JTextField(host));
        field(passwordField);
        remember.setSelected(rememberedName != null);
        remember.setOpaque(false);
        remember.setForeground(new Color(199, 202, 207));

        JPanel base = new JPanel();
        base.setLayout(new BoxLayout(base, BoxLayout.Y_AXIS));
        base.setBackground(BACKGROUND);
        base.setBorder(BorderFactory.createEmptyBorder(26, 34, 24, 34));

        JLabel brand = new JLabel(AppInfo.NAME);
        brand.setForeground(ACCENT);
        brand.setFont(Theme.font(Font.BOLD, 26));
        title.setForeground(MUTED);
        title.setFont(Theme.font(Font.BOLD, 12));
        status.setForeground(new Color(190, 195, 203));
        connect.setFont(Theme.font(Font.BOLD, 14));
        connect.setForeground(Color.WHITE);
        connect.setBackgroundOverride(Theme.accent()); // the same green as the Send button
        connect.setCorner(44);
        connect.setPreferredSize(new Dimension(300, 44));
        connect.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        switchMode.setContentAreaFilled(false);
        switchMode.setBorderPainted(false);
        switchMode.setFocusPainted(false);
        switchMode.setForeground(ACCENT);

        for (Component part : new Component[] { brand, title, Box.createVerticalStrut(16),
                label("ACCOUNT NAME"), nameField, Box.createVerticalStrut(8),
                label("PASSWORD"), passwordField, Box.createVerticalStrut(8),
                label("SERVER IP"), hostField, Box.createVerticalStrut(8),
                remember, status, Box.createVerticalStrut(6), connect, Box.createVerticalStrut(4), switchMode }) {
            ((javax.swing.JComponent) part).setAlignmentX(Component.LEFT_ALIGNMENT);
            base.add(part);
        }

        connect.addActionListener(event -> submit());
        switchMode.addActionListener(event -> {
            registerMode = !registerMode;
            status.setText(" ");
            applyMode();
        });
        applyMode();

        dialog.setIconImages(SharpIcon.windowIcons("/images/icon.png")); // our icon instead of the default Java one
        brand.setIcon(SharpIcon.fromResource("/images/icon.png", 44));
        brand.setIconTextGap(12);
        // our own title bar on top instead of the Windows one
        JPanel window = new JPanel(new java.awt.BorderLayout());
        window.add(new TitleBar(dialog, AppInfo.NAME), java.awt.BorderLayout.NORTH);
        window.add(base, java.awt.BorderLayout.CENTER);

        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(window);
        dialog.getRootPane().setDefaultButton(connect);
        dialog.setResizable(false);
        dialog.setSize(380, 626);
        dialog.setLocationRelativeTo(null);
    }

    /** Shows the dialog and blocks until the user signs in (a Result) or closes it (null). */
    static Result show(String rememberedName, String host) {
        LoginDialog login = new LoginDialog(rememberedName, host);
        login.dialog.setVisible(true);
        return login.result;
    }

    private void applyMode() {
        title.setText(registerMode ? "CREATE A NEW ACCOUNT" : "SIGN IN");
        connect.setText(registerMode ? "Create account" : "Sign in");
        switchMode.setText(registerMode ? "I already have an account" : "New here? Create an account");
        dialog.setTitle(registerMode ? AppInfo.NAME + " — Create account" : AppInfo.NAME + " — Sign in");
    }

    private void submit() {
        String name = nameField.getText().replace('\n', ' ').replace('\r', ' ').trim();
        String password = new String(passwordField.getPassword());
        String host = hostField.getText().trim();
        if (name.isEmpty() || name.length() > 32) {
            status.setText("Enter an account name between 1 and 32 characters.");
            return;
        }
        if (password.isEmpty()) {
            status.setText("Enter your password.");
            return;
        }
        if (!isIpAddress(host)) {
            status.setText("Enter a valid IPv4 or IPv6 server address.");
            return;
        }
        String mode = registerMode ? "REGISTER" : "LOGIN";
        boolean rememberName = remember.isSelected();
        status.setText("Connecting...");
        connect.setEnabled(false);
        Thread worker = new Thread(() -> {
            try {
                ChatClient client = ChatClient.login(host, PORT, mode, name, password);
                SwingUtilities.invokeLater(() -> {
                    result = new Result(client, rememberName);
                    dialog.dispose();
                });
            } catch (IOException error) {
                SwingUtilities.invokeLater(() -> {
                    status.setText(error instanceof java.net.ConnectException || error instanceof java.net.SocketTimeoutException
                            ? "Could not connect to " + host + ":" + PORT + " — " + error.getMessage()
                            : error.getMessage());
                    connect.setEnabled(true);
                });
            }
        }, "nova-login");
        worker.setDaemon(true);
        worker.start();
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(Theme.font(Font.BOLD, 10));
        return label;
    }

    private static <T extends JTextField> T field(T field) {
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setBackground(FIELD);
        field.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        field.setPreferredSize(new Dimension(300, 40));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return field;
    }

    private static boolean isIpAddress(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        if (address.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(address).getAddress().length == 16;
            } catch (IOException ex) {
                return false;
            }
        }
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("\\d{1,3}") || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
