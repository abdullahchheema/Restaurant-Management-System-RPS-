package rps.ui;

import rps.app.Session;
import rps.db.Db;
import rps.db.DatabaseException;
import rps.db.StaffDao;
import rps.model.Role;
import rps.model.Staff;
import rps.ui.icon.LineIcon;
import rps.ui.theme.EmptyState;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.Validators;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public final class StaffAdminPanel extends JPanel {

    private final Db db;
    private final Session session;
    private final StaffDao staffDao;

    private final DefaultListModel<Staff> staffModel = new DefaultListModel<>();
    private final JList<Staff> staffList = new JList<>(staffModel);
    private final CardLayout staffCards = new CardLayout();
    private final JPanel staffCardHolder = new JPanel(staffCards);
    private final EmptyState staffEmpty = new EmptyState(LineIcon.EDIT, "No active staff",
        "Add a staff member to get started.");

    public StaffAdminPanel(Db db, Session session) {
        this.db = db;
        this.session = session;
        this.staffDao = new StaffDao(db);

        session.require(Role.MANAGER);

        setLayout(new BorderLayout(10, 14));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG));

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(UiFactory.title("Staff Management"), BorderLayout.WEST);
        add(titleRow, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(10, 10));
        body.setOpaque(false);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(UiFactory.heading("Active Staff"), BorderLayout.WEST);
        JButton addBtn = UiFactory.primaryButton("Add Staff");
        addBtn.addActionListener(e -> openEditor(null));
        header.add(addBtn, BorderLayout.EAST);
        body.add(header, BorderLayout.NORTH);

        staffList.setFont(Theme.FONT_BODY);
        staffList.setCellRenderer((list, staff, index, isSelected, hasFocus) -> {
            String fg = MenuAdminPanel.toHex(isSelected ? Theme.ON_PRIMARY : Theme.TEXT_MUTED);
            JLabel l = new JLabel("<html><b>" + staff.fullName() + "</b>&nbsp;&nbsp;"
                + "<span style='color:" + fg + "'>" + staff.role().name() + "  ·  ID " + staff.id() + "</span></html>");
            l.setFont(Theme.FONT_BODY);
            l.setOpaque(true);
            l.setBackground(isSelected ? Theme.PRIMARY : Theme.SURFACE);
            l.setForeground(isSelected ? Theme.ON_PRIMARY : Theme.TEXT);
            l.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
            return l;
        });
        staffCardHolder.setOpaque(false);
        staffCardHolder.add(new JScrollPane(staffList), "list");
        staffCardHolder.add(staffEmpty, "empty");
        body.add(staffCardHolder, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        actions.setOpaque(false);
        JButton edit = UiFactory.secondaryButton("Edit");
        edit.addActionListener(e -> {
            Staff s = staffList.getSelectedValue();
            if (s != null) openEditor(s);
        });
        JButton deactivate = UiFactory.secondaryButton("Deactivate");
        deactivate.addActionListener(e -> deactivateSelected());
        actions.add(edit);
        actions.add(deactivate);
        add(actions, BorderLayout.SOUTH);

        reload();
    }

    private void reload() {
        Busy.call(this, staffDao::listActive)
            .message("Loading staff…")
            .onSuccess(staff -> {
                staffModel.clear();
                for (Staff s : staff) staffModel.addElement(s);
                staffCards.show(staffCardHolder, staff.isEmpty() ? "empty" : "list");
            })
            .onError(e -> JOptionPane.showMessageDialog(this, "Could not load staff: " + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE))
            .start();
    }

    private void deactivateSelected() {
        Staff s = staffList.getSelectedValue();
        if (s == null) return;
        if (s.id() == session.staffId()) {
            JOptionPane.showMessageDialog(this, "You cannot deactivate your own account.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "Deactivate " + s.fullName() + "?",
            "Confirm", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            staffDao.deactivate(s.id());
            reload();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openEditor(Staff editing) {
        JTextField firstName = UiFactory.textField(16);
        JTextField lastName = UiFactory.textField(16);
        // Masked, and never pre-filled: only a one-way hash is stored, so there is no
        // current password to show. On edit, leaving it blank keeps the existing one.
        JPasswordField password = UiFactory.passwordField(16);
        JComboBox<Role> role = new JComboBox<>(Role.values());

        if (editing != null) {
            firstName.setText(editing.firstName());
            lastName.setText(editing.lastName());
            role.setSelectedItem(editing.role());
        }

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(UiFactory.label("First name")); form.add(firstName);
        form.add(UiFactory.label("Last name")); form.add(lastName);
        form.add(UiFactory.label(editing == null ? "Password" : "New password"));
        form.add(password);
        if (editing != null) {
            form.add(UiFactory.label(""));
            form.add(UiFactory.muted("Leave blank to keep current password"));
        }
        form.add(UiFactory.label("Role")); form.add(role);

        int result = JOptionPane.showConfirmDialog(this, form,
            editing == null ? "Add Staff" : "Edit Staff", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String enteredPassword = new String(password.getPassword());
        if (Validators.isBlank(firstName.getText()) || Validators.isBlank(lastName.getText())) {
            JOptionPane.showMessageDialog(this, "First and last name are required.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (editing == null && Validators.isBlank(enteredPassword)) {
            JOptionPane.showMessageDialog(this, "A password is required for a new staff member.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            Role selectedRole = (Role) role.getSelectedItem();
            if (editing == null) {
                staffDao.create(firstName.getText(), lastName.getText(), enteredPassword, selectedRole);
            } else {
                // Blank -> StaffDao.update leaves the stored hash untouched.
                staffDao.update(editing.id(), firstName.getText(), lastName.getText(),
                    enteredPassword, selectedRole);
            }
            reload();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
