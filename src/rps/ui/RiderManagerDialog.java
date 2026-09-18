package rps.ui;

import rps.db.DatabaseException;
import rps.db.DeliveryDao;
import rps.model.Rider;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.Validators;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Add riders and toggle whether they're active — a rider with no orders currently
 *  assigned can be deactivated rather than deleted, so past runs still show their name. */
final class RiderManagerDialog extends JDialog {

    private final DeliveryDao deliveryDao;
    private final Runnable onChanged;
    private final JPanel listPanel = new JPanel();
    private final JLabel listEmptyLabel = UiFactory.muted("No riders yet.");
    private final JTextField nameField = UiFactory.textField(14);
    private final JTextField phoneField = UiFactory.textField(12);
    private final JLabel errorLabel = UiFactory.errorText(" ");

    RiderManagerDialog(Component parent, DeliveryDao deliveryDao, Runnable onChanged) {
        super(SwingUtilities.getWindowAncestor(parent), "Riders", ModalityType.APPLICATION_MODAL);
        this.deliveryDao = deliveryDao;
        this.onChanged = onChanged;

        getContentPane().setBackground(Theme.SURFACE);
        setLayout(new BorderLayout());

        // Everything — the rider list AND the add-rider form — scrolls together as one
        // column, rather than a fixed-height nested scroll pane for the list plus a
        // separately-sized form below it. That split previously meant the list's
        // fixed height reserved dead space when there were few riders, AND the form
        // below it could be taller than what remained and get cut off at the dialog's
        // bottom edge (setPreferredSize on the dialog is a hard cap — pack() doesn't
        // grow past it to fit content that doesn't).
        JPanel content = new JPanel();
        content.setOpaque(true);
        content.setBackground(Theme.SURFACE);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel heading = UiFactory.title("Riders");
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);
        content.add(Box.createVerticalStrut(14));

        JLabel addHeading = UiFactory.heading("Add rider");
        addHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(addHeading);
        content.add(Box.createVerticalStrut(10));

        JPanel fields = new JPanel();
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameField.putClientProperty("JTextField.placeholderText", "Name");
        phoneField.putClientProperty("JTextField.placeholderText", "Phone (optional)");
        nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        phoneField.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameField.getPreferredSize().height));
        phoneField.setMaximumSize(new Dimension(Integer.MAX_VALUE, phoneField.getPreferredSize().height));
        fields.add(nameField);
        fields.add(Box.createVerticalStrut(8));
        fields.add(phoneField);
        content.add(fields);
        content.add(Box.createVerticalStrut(10));

        JButton addBtn = UiFactory.primaryButton("Add Rider");
        addBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        addBtn.addActionListener(e -> addRider());
        content.add(addBtn);

        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(Box.createVerticalStrut(6));
        content.add(errorLabel);

        content.add(Box.createVerticalStrut(18));
        content.add(divider());
        content.add(Box.createVerticalStrut(18));

        listPanel.setOpaque(false);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(listPanel);

        ScrollableColumn scrollHost = new ScrollableColumn(new BorderLayout());
        scrollHost.setBackground(Theme.SURFACE);
        scrollHost.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(scrollHost,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.SURFACE);
        add(scroll, BorderLayout.CENTER);

        JButton close = UiFactory.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        buttons.setBackground(Theme.SURFACE);
        buttons.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        buttons.add(close);
        add(buttons, BorderLayout.SOUTH);

        reload();
        // A minimum only — pack() measures the real content height (capped by this
        // screen's own visible height via the outer scroll pane if there are many
        // riders) rather than forcing a fixed size that content might not fit inside.
        setMinimumSize(new Dimension(420, 360));
        setPreferredSize(new Dimension(440, 520));
        pack();
        setLocationRelativeTo(parent);
    }

    private void reload() {
        try {
            List<Rider> riders = deliveryDao.listRiders(false);
            listPanel.removeAll();
            if (riders.isEmpty()) {
                listEmptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                listPanel.add(listEmptyLabel);
            } else {
                for (Rider r : riders) {
                    listPanel.add(riderRow(r));
                }
            }
            listPanel.revalidate();
            listPanel.repaint();
            pack();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JComponent riderRow(Rider r) {
        // GridBagLayout, not BorderLayout — BorderLayout.WEST/EAST always stretch their
        // component to the container's FULL height. With a fixed row-height cap that
        // was shorter than the button's own preferred height (padding included), the
        // button got forced shorter than it wanted to be, squashing it against the
        // divider below. GridBagLayout's default CENTER anchor sizes the row to its
        // tallest child instead and centers both the name and the button within that,
        // so neither is ever forced smaller than its natural size.
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
            BorderFactory.createEmptyBorder(10, 4, 10, 4)));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel name = UiFactory.labelBold(r.name());
        if (!r.active()) name.setForeground(Theme.TEXT_MUTED);
        text.add(name);
        if (r.phone() != null && !r.phone().isBlank()) {
            text.add(UiFactory.muted(r.phone()));
        }
        GridBagConstraints textConstraints = new GridBagConstraints();
        textConstraints.gridx = 0;
        textConstraints.weightx = 1;
        textConstraints.anchor = GridBagConstraints.WEST;
        row.add(text, textConstraints);

        JButton toggle = UiFactory.secondaryButton(r.active() ? "Deactivate" : "Activate");
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.gridx = 1;
        buttonConstraints.anchor = GridBagConstraints.EAST;
        row.add(toggle, buttonConstraints);
        toggle.addActionListener(e -> {
            try {
                deliveryDao.setRiderActive(r.id(), !r.active());
                reload();
                onChanged.run();
            } catch (DatabaseException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        return row;
    }

    private static JComponent divider() {
        JPanel d = new JPanel();
        d.setBackground(Theme.BORDER);
        d.setPreferredSize(new Dimension(1, 1));
        d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        d.setAlignmentX(Component.LEFT_ALIGNMENT);
        return d;
    }

    private void addRider() {
        String name = nameField.getText().trim();
        if (!Validators.isValidName(name)) {
            errorLabel.setText("Enter a valid name.");
            return;
        }
        try {
            deliveryDao.createRider(name, phoneField.getText());
            nameField.setText("");
            phoneField.setText("");
            errorLabel.setText(" ");
            reload();
            onChanged.run();
        } catch (DatabaseException e) {
            errorLabel.setText(e.getMessage());
        }
    }
}
