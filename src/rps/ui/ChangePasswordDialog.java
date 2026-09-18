package rps.ui;

import rps.db.StaffDao;
import rps.model.Staff;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.Validators;

import javax.swing.*;
import java.awt.*;

/**
 * Forces a staff member off the password the app ships with, before they reach the till.
 *
 * <p>Migration 2 seeds the first manager as id 1 / admin123, and that pair has to be
 * written down in the install notes for the shop to be able to sign in at all — so on
 * every install it is public knowledge. Leaving it in place means anyone who walks up to
 * the machine has manager rights: void orders, edit the menu, read the day's takings.
 * There is deliberately no "skip" or "remind me later": the dialog either ends with a new
 * password stored, or the sign-in is abandoned.
 */
final class ChangePasswordDialog extends JDialog {

    private final StaffDao staffDao;
    private final Staff staff;

    private final JPasswordField newPassword = new JPasswordField();
    private final JPasswordField confirmPassword = new JPasswordField();
    private final JLabel errorLabel = UiFactory.errorText(" ");

    private boolean changed;

    private ChangePasswordDialog(Window owner, StaffDao staffDao, Staff staff) {
        super(owner, "Choose a new password", ModalityType.APPLICATION_MODAL);
        this.staffDao = staffDao;
        this.staff = staff;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new JPanel();
        content.setBackground(Theme.BACKGROUND);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(24, 28, 22, 28));

        JLabel heading = new JLabel("Set a new password");
        heading.setFont(Theme.FONT_TITLE);
        heading.setForeground(Theme.TEXT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);

        content.add(Box.createVerticalStrut(8));
        JLabel why = new JLabel("<html><body style='width:330px'>"
            + "This account is still using the password the system was installed with, "
            + "which is not private. Choose a new one before continuing.</body></html>");
        why.setFont(Theme.FONT_BODY);
        why.setForeground(Theme.TEXT_MUTED);
        why.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(why);

        content.add(Box.createVerticalStrut(18));
        content.add(field("New password", newPassword));
        content.add(Box.createVerticalStrut(12));
        content.add(field("Confirm new password", confirmPassword));

        content.add(Box.createVerticalStrut(6));
        JLabel rule = UiFactory.muted("At least " + Validators.MIN_PASSWORD_LENGTH + " characters.");
        rule.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(rule);

        content.add(Box.createVerticalStrut(8));
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(errorLabel);

        content.add(Box.createVerticalStrut(14));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton cancel = UiFactory.secondaryButton("Cancel sign-in");
        cancel.addActionListener(e -> dispose());
        JButton save = UiFactory.primaryButton("Save and continue");
        save.addActionListener(e -> save());
        buttons.add(cancel);
        buttons.add(save);
        content.add(buttons);

        setContentPane(content);
        getRootPane().setDefaultButton(save);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog and returns true only once a new password has actually been stored. */
    static boolean forceChange(Window owner, StaffDao staffDao, Staff staff) {
        ChangePasswordDialog dialog = new ChangePasswordDialog(owner, staffDao, staff);
        dialog.setVisible(true);
        return dialog.changed;
    }

    private JComponent field(String label, JPasswordField input) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel caption = UiFactory.muted(label);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setMaximumSize(new Dimension(Integer.MAX_VALUE, input.getPreferredSize().height));
        row.add(caption);
        row.add(Box.createVerticalStrut(4));
        row.add(input);
        return row;
    }

    private void save() {
        char[] entered = newPassword.getPassword();
        char[] repeated = confirmPassword.getPassword();
        try {
            String candidate = new String(entered);
            if (!java.util.Arrays.equals(entered, repeated)) {
                errorLabel.setText("The two passwords don't match.");
                return;
            }
            String problem = Validators.passwordProblem(candidate);
            if (problem != null) {
                errorLabel.setText(problem);
                return;
            }
            errorLabel.setText(" ");
            Busy.call((JComponent) getContentPane(), () -> {
                    staffDao.changePassword(staff.id(), candidate);
                    return null;
                })
                .message("Saving…")
                .onSuccess(ignored -> {
                    changed = true;
                    dispose();
                })
                .onError(ex -> errorLabel.setText(ex.getMessage()))
                .start();
        } finally {
            java.util.Arrays.fill(entered, '\0');
            java.util.Arrays.fill(repeated, '\0');
        }
    }
}
