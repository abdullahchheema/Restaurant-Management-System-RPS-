package rps.ui;

import rps.app.Session;
import rps.backup.OffsiteBackupService;
import rps.db.Db;
import rps.db.StaffDao;
import rps.ui.icon.LineIcon;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;

/**
 * Shown first, before anything else is reachable. MainWindow can only be constructed
 * with a non-null Session, obtained here — see plan Phase 3 "structural enforcement".
 */
public final class LoginWindow extends JFrame {

    private final Db db;
    private final OffsiteBackupService offsiteBackupService;
    private final StaffDao staffDao;

    private final JTextField idField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JLabel errorLabel;

    public LoginWindow(Db db, OffsiteBackupService offsiteBackupService) {
        super("Royal Pizza Sahowala — Login");
        this.db = db;
        this.offsiteBackupService = offsiteBackupService;
        this.staffDao = new StaffDao(db);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        getContentPane().setBackground(Theme.BACKGROUND);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(44, 48, 36, 48));

        RpsLogoBadge logo = new RpsLogoBadge(168);
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(logo);
        content.add(Box.createVerticalStrut(22));

        JLabel title = new JLabel("Royal Pizza Sahowala");
        title.setFont(Theme.FONT_BRAND.deriveFont(Font.BOLD, 30f));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(title);

        content.add(Box.createVerticalStrut(6));
        JLabel subtitle = new JLabel("Staff sign-in");
        subtitle.setFont(Theme.FONT_TITLE.deriveFont(Font.PLAIN, 19f));
        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(subtitle);

        content.add(Box.createVerticalStrut(34));

        PillField idPill = new PillField("Staff ID", idField, "Enter Staff ID", null);
        idPill.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(idPill);

        content.add(Box.createVerticalStrut(16));

        JToggleButton eyeToggle = passwordVisibilityToggle();
        PillField passwordPill = new PillField("Password", passwordField, "Enter Password", eyeToggle);
        passwordPill.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(passwordPill);

        content.add(Box.createVerticalStrut(10));

        errorLabel = UiFactory.errorText(" ");
        errorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        errorLabel.setHorizontalAlignment(SwingConstants.CENTER);
        content.add(errorLabel);

        content.add(Box.createVerticalStrut(4));
        JLabel forgot = new JLabel("<html><u>Forgot Password?</u></html>");
        forgot.setFont(Theme.FONT_BODY);
        forgot.setForeground(Theme.TEXT_MUTED);
        forgot.setAlignmentX(Component.RIGHT_ALIGNMENT);
        forgot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        forgot.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                JOptionPane.showMessageDialog(LoginWindow.this,
                    "Passwords are reset by a manager from the Staff tab — ask them to set a new one for you.",
                    "Forgot Password", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        JPanel forgotRow = new JPanel(new BorderLayout());
        forgotRow.setOpaque(false);
        forgotRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        forgotRow.setMaximumSize(new Dimension(PillField.WIDTH, 24));
        forgotRow.add(forgot, BorderLayout.EAST);
        content.add(forgotRow);

        content.add(Box.createVerticalStrut(22));

        PillButton loginButton = new PillButton("Log in");
        loginButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        loginButton.setMaximumSize(new Dimension(PillField.WIDTH, 64));
        loginButton.setPreferredSize(new Dimension(PillField.WIDTH, 64));
        loginButton.addActionListener(this::attemptLogin);
        content.add(loginButton);

        content.add(Box.createVerticalStrut(20));
        JLabel footer = UiFactory.muted("Need help signing in? Ask your manager.");
        footer.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(footer);

        setLayout(new BorderLayout());
        add(content, BorderLayout.CENTER);

        getRootPane().setDefaultButton(loginButton);
        idField.addActionListener(e -> passwordField.requestFocusInWindow());
        passwordField.addActionListener(this::attemptLogin);

        setPreferredSize(new Dimension(560, 840));
        pack();
        setLocationRelativeTo(null);
    }

    private JToggleButton passwordVisibilityToggle() {
        JToggleButton toggle = new JToggleButton(LineIcon.EYE_HIDE.of(20, Theme.TEXT_MUTED));
        toggle.setSelectedIcon(LineIcon.EYE_SHOW.of(20, Theme.TEXT_MUTED));
        toggle.setOpaque(false);
        toggle.setContentAreaFilled(false);
        toggle.setBorderPainted(false);
        toggle.setFocusPainted(false);
        toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        char defaultEcho = passwordField.getEchoChar();
        toggle.addActionListener(e ->
            passwordField.setEchoChar(toggle.isSelected() ? (char) 0 : defaultEcho));
        return toggle;
    }

    private void attemptLogin(ActionEvent e) {
        String idText = idField.getText().trim();
        char[] pw = passwordField.getPassword();

        if (idText.isEmpty() || pw.length == 0) {
            showError("Enter your staff ID and password.");
            java.util.Arrays.fill(pw, '\0');
            return;
        }

        int id;
        try {
            id = Integer.parseInt(idText);
        } catch (NumberFormatException nfe) {
            showError("Staff ID must be a number.");
            java.util.Arrays.fill(pw, '\0');
            return;
        }

        String password = new String(pw);
        java.util.Arrays.fill(pw, '\0');
        showError(" ");

        // Off the EDT: a slow database used to freeze this window with no feedback at all.
        Busy.call((JComponent) getContentPane(), () -> staffDao.authenticate(id, password))
            .message("Signing in…")
            .onSuccess(staff -> {
                if (staff == null) {
                    // One generic message for both "not found" and "wrong password" —
                    // splitting them is a user-enumeration leak (see plan Phase 3).
                    showError("Incorrect ID or password.");
                    return;
                }
                // An account still on the shipped default password never reaches the till:
                // that pair is published in the install notes, so it protects nothing.
                if (rps.util.Validators.isUnacceptablePassword(password)
                    && !ChangePasswordDialog.forceChange(this, staffDao, staff)) {
                    showError("Sign-in cancelled — the password must be changed first.");
                    passwordField.setText("");
                    return;
                }
                Session session = new Session(staff);
                new MainWindow(db, session, offsiteBackupService).setVisible(true);
                dispose();
            })
            .onError(ex -> showError("Could not reach the database: " + ex.getMessage()))
            .start();
    }

    private void showError(String message) {
        errorLabel.setText(message);
    }

    /** A rounded field with its label built into the left edge of the box itself, rather
     *  than a separate label above a plain field — the field IS the label's container. */
    private static final class PillField extends JPanel {
        static final int WIDTH = 440;
        private static final int HEIGHT = 74;
        private static final int RADIUS = HEIGHT;

        PillField(String labelText, JTextField field, String placeholder, JComponent trailing) {
            super(new BorderLayout(14, 0));
            setOpaque(false);
            setPreferredSize(new Dimension(WIDTH, HEIGHT));
            setMaximumSize(new Dimension(WIDTH, HEIGHT));
            setBorder(BorderFactory.createEmptyBorder(0, 26, 0, trailing != null ? 14 : 26));

            JLabel label = new JLabel(labelText);
            label.setFont(Theme.FONT_BODY_BOLD.deriveFont(Font.BOLD, 18f));
            label.setForeground(Theme.TEXT);
            add(label, BorderLayout.WEST);

            field.putClientProperty("JTextField.placeholderText", placeholder);
            field.setFont(Theme.FONT_BODY.deriveFont(18f));
            field.setForeground(Theme.TEXT_MUTED);
            field.setBorder(null);
            field.setOpaque(false);
            add(field, BorderLayout.CENTER);

            if (trailing != null) {
                add(trailing, BorderLayout.EAST);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.SURFACE);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS);
            g2.setStroke(new BasicStroke(1.6f));
            g2.setColor(Theme.BORDER.darker());
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Fully rounded (pill) button — the global Button.arc UIManager setting only covers
     *  the app's standard 6px radius, not a true pill; painted directly instead. */
    private static final class PillButton extends JButton {
        PillButton(String text) {
            super(text);
            setFont(Theme.FONT_HEADING.deriveFont(Font.BOLD, 19f));
            setForeground(Theme.ON_PRIMARY);
            setBackground(Theme.PRIMARY);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { setBackground(Theme.PRIMARY_DARK); repaint(); }
                @Override public void mouseExited(MouseEvent e) { setBackground(Theme.PRIMARY); repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = getHeight();
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Hand-painted circular wordmark badge — the shop has no supplied logo image, so
     *  this is a ring, a pizza-slice glyph from the shared icon family, and "RPS" set in
     *  the brand typeface, composed to read as one mark rather than three stacked parts. */
    private static final class RpsLogoBadge extends JComponent {
        private final int size;

        RpsLogoBadge(int size) {
            this.size = size;
            setPreferredSize(new Dimension(size, size));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color gold = Theme.ACCENT_DEEP;
            float ringStroke = size * 0.028f;
            g2.setStroke(new BasicStroke(ringStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(gold);
            float pad = ringStroke;
            g2.draw(new Ellipse2D.Float(pad, pad, size - pad * 2, size - pad * 2));

            int iconSize = Math.round(size * 0.4f);
            Icon pizza = LineIcon.CATEGORY_PIZZA.of(iconSize, gold);
            int iconX = (size - iconSize) / 2;
            int iconY = Math.round(size * 0.14f);
            pizza.paintIcon(this, g2, iconX, iconY);

            Font font = Theme.FONT_BRAND.deriveFont(Font.BOLD, size * 0.22f);
            g2.setFont(font);
            g2.setColor(Theme.TEXT);
            FontMetrics fm = g2.getFontMetrics();
            String text = "RPS";
            int textX = (size - fm.stringWidth(text)) / 2;
            int textY = Math.round(size * 0.74f);
            g2.drawString(text, textX, textY);

            g2.dispose();
        }
    }
}
