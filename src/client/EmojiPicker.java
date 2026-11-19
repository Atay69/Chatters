package client;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;

/** The emoji popup: a grid of emoji pictures; clicking one types it into the message box. */
final class EmojiPicker {
    private EmojiPicker() {
    }

    static void show(Component anchor, JTextArea input) {
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(Theme.dark ? new Color(70, 82, 104) : new Color(200, 208, 222)));
        JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
        grid.setBackground(Theme.dark ? new Color(28, 34, 50) : new Color(250, 251, 254));
        grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        for (Emoji emoji : Emoji.all()) {
            JButton button = new JButton(new SharpIcon(emoji.image(), 34));
            button.setContentAreaFilled(false);
            button.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setToolTipText(emoji.name);
            button.addActionListener(event -> {
                input.replaceSelection(emoji.text()); // typed at the cursor (replaces the selected text)
                popup.setVisible(false);
                input.requestFocusInWindow();
            });
            grid.add(button);
        }
        grid.setPreferredSize(new Dimension(334, 336)); // fits all emoji without scrolling
        popup.add(grid);
        popup.show(anchor, 0, -grid.getPreferredSize().height);
    }
}
