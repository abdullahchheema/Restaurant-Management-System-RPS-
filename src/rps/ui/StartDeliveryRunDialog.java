package rps.ui;

import rps.db.DatabaseException;
import rps.db.DeliveryDao;
import rps.model.DeliveryOrderSummary;
import rps.model.DeliveryRun;
import rps.model.Rider;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.Money;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pick a rider and one or more unassigned orders (any order type — Pending or already
 *  Payment Received, e.g. prepaid) to send out together as one run. */
final class StartDeliveryRunDialog extends JDialog {

    private final DeliveryDao deliveryDao;
    private final int staffId;
    private final String staffName;
    private DeliveryRun created;

    private final JComboBox<Rider> riderCombo = new JComboBox<>();
    private final JPanel orderList = new JPanel();
    private final Map<Long, JCheckBox> checkboxes = new LinkedHashMap<>();
    private final Map<Long, DeliveryOrderSummary> orderById = new LinkedHashMap<>();
    private final JLabel selectedTotalLabel = UiFactory.labelBold(" ");
    private final JLabel errorLabel = UiFactory.errorText(" ");
    private final JButton startButton = UiFactory.primaryButton("Start Run");

    StartDeliveryRunDialog(Component parent, DeliveryDao deliveryDao, int staffId, String staffName) {
        super(SwingUtilities.getWindowAncestor(parent), "Start Delivery Run", ModalityType.APPLICATION_MODAL);
        this.deliveryDao = deliveryDao;
        this.staffId = staffId;
        this.staffName = staffName;

        getContentPane().setBackground(Theme.SURFACE);
        setLayout(new BorderLayout(0, 16));

        JPanel content = new JPanel();
        content.setBackground(Theme.SURFACE);
        content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        content.add(UiFactory.title("Start Delivery Run"));
        content.add(Box.createVerticalStrut(14));

        content.add(UiFactory.muted("RIDER"));
        content.add(Box.createVerticalStrut(6));
        riderCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        riderCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        content.add(riderCombo);
        content.add(Box.createVerticalStrut(14));

        content.add(UiFactory.muted("UNASSIGNED ORDERS"));
        content.add(Box.createVerticalStrut(6));

        orderList.setOpaque(false);
        orderList.setLayout(new BoxLayout(orderList, BoxLayout.Y_AXIS));
        ScrollableColumn wrap = new ScrollableColumn(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(orderList, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.setPreferredSize(new Dimension(440, 260));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(scroll);
        content.add(Box.createVerticalStrut(10));

        selectedTotalLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(selectedTotalLabel);
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(errorLabel);

        // content itself is wrapped in an outer scroll pane too, not added directly —
        // a fixed setPreferredSize on the DIALOG is a hard cap that pack() won't grow
        // past, so if the rider combo + labels + the order list's own 260px scroll box
        // together needed more height than that cap allowed, the bottom of this panel
        // would be cut off at the window edge with no way to reach it (the same bug
        // fixed in RiderManagerDialog, OrderDetailDialog, and the PosPanel/
        // OrderEditDialog ticket columns). Wrapping it here means excess content
        // scrolls instead of disappearing.
        ScrollableColumn scrollHost = new ScrollableColumn(new BorderLayout());
        scrollHost.setBackground(Theme.SURFACE);
        scrollHost.add(content, BorderLayout.NORTH);
        JScrollPane outerScroll = new JScrollPane(scrollHost,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        outerScroll.setBorder(null);
        outerScroll.getViewport().setBackground(Theme.SURFACE);
        add(outerScroll, BorderLayout.CENTER);

        JButton cancel = UiFactory.secondaryButton("Cancel");
        cancel.addActionListener(e -> dispose());
        startButton.addActionListener(e -> start());
        startButton.setEnabled(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        buttons.setBackground(Theme.SURFACE);
        buttons.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        buttons.add(cancel);
        buttons.add(startButton);
        add(buttons, BorderLayout.SOUTH);

        loadRiders();
        loadOrders();

        setMinimumSize(new Dimension(460, 420));
        setPreferredSize(new Dimension(500, 620));
        pack();
        setLocationRelativeTo(parent);
    }

    private void loadRiders() {
        try {
            List<Rider> riders = deliveryDao.listRiders(true);
            for (Rider r : riders) riderCombo.addItem(r);
            if (riders.isEmpty()) {
                errorLabel.setText("No active riders — add one first (Manage Riders).");
            }
        } catch (DatabaseException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private void loadOrders() {
        try {
            java.time.LocalDate today = rps.db.OrderDao.businessDate(java.time.ZonedDateTime.now());
            List<DeliveryOrderSummary> orders = deliveryDao.listUnassignedOrders(today, today);
            orderList.removeAll();
            checkboxes.clear();
            orderById.clear();
            if (orders.isEmpty()) {
                JLabel empty = UiFactory.muted("No unassigned orders today.");
                empty.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 8));
                orderList.add(empty);
            }
            for (DeliveryOrderSummary o : orders) {
                orderById.put(o.orderId(), o);
                orderList.add(orderRow(o));
            }
            orderList.revalidate();
            orderList.repaint();
            updateSelectedTotal();
        } catch (DatabaseException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private JComponent orderRow(DeliveryOrderSummary o) {
        // GridBagLayout, not BorderLayout — BorderLayout.WEST/CENTER/EAST all stretch
        // to the container's full height, so a fixed maximumSize height cap here would
        // squash the checkbox/amount against a 3-line text block once the items line
        // was added (the same bug just fixed on the Riders dialog's row). GridBagLayout
        // sizes the row to its tallest child and centers each column within that.
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        JCheckBox check = new JCheckBox();
        check.setOpaque(false);
        check.addActionListener(e -> updateSelectedTotal());
        checkboxes.put(o.orderId(), check);
        GridBagConstraints checkConstraints = new GridBagConstraints();
        checkConstraints.gridx = 0;
        checkConstraints.insets = new Insets(0, 0, 0, 8);
        row.add(check, checkConstraints);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        String who = o.customerName() == null || o.customerName().isBlank() ? "" : "  ·  " + o.customerName();
        text.add(UiFactory.labelBold(o.orderNumber() + who));
        String subtitle = o.type().label();
        if (o.deliveryAddress() != null && !o.deliveryAddress().isBlank()) {
            String addr = o.deliveryAddress();
            subtitle += "  ·  " + (addr.length() > 40 ? addr.substring(0, 40) + "…" : addr);
        }
        text.add(UiFactory.muted(subtitle));
        if (o.itemsSummary() != null && !o.itemsSummary().isBlank()) {
            String items = o.itemsSummary();
            text.add(UiFactory.muted(items.length() > 55 ? items.substring(0, 55) + "…" : items));
        }
        GridBagConstraints textConstraints = new GridBagConstraints();
        textConstraints.gridx = 1;
        textConstraints.weightx = 1;
        textConstraints.fill = GridBagConstraints.HORIZONTAL;
        textConstraints.anchor = GridBagConstraints.WEST;
        row.add(text, textConstraints);

        String amountText = o.status() == rps.model.OrderStatus.PAYMENT_RECEIVED
            ? "Paid (" + o.total().format() + ")"
            : "Due " + o.balanceDue().format();
        JLabel amount = UiFactory.labelBold(amountText);
        amount.setForeground(o.status() == rps.model.OrderStatus.PAYMENT_RECEIVED ? Theme.STATUS_COMPLETED : Theme.STATUS_PENDING);
        GridBagConstraints amountConstraints = new GridBagConstraints();
        amountConstraints.gridx = 2;
        amountConstraints.insets = new Insets(0, 8, 0, 0);
        row.add(amount, amountConstraints);

        return row;
    }

    private void updateSelectedTotal() {
        Money dueTotal = Money.ZERO;
        int count = 0;
        for (Map.Entry<Long, JCheckBox> e : checkboxes.entrySet()) {
            if (e.getValue().isSelected()) {
                count++;
                DeliveryOrderSummary o = orderById.get(e.getKey());
                if (o.status() != rps.model.OrderStatus.PAYMENT_RECEIVED) {
                    dueTotal = dueTotal.add(o.balanceDue());
                }
            }
        }
        selectedTotalLabel.setText(count == 0 ? " "
            : count + " order" + (count == 1 ? "" : "s") + " selected  ·  Rider should collect " + dueTotal.format());
        startButton.setEnabled(count > 0 && riderCombo.getSelectedItem() != null);
    }

    private void start() {
        Rider rider = (Rider) riderCombo.getSelectedItem();
        if (rider == null) {
            errorLabel.setText("Choose a rider.");
            return;
        }
        List<Long> selected = new ArrayList<>();
        for (Map.Entry<Long, JCheckBox> e : checkboxes.entrySet()) {
            if (e.getValue().isSelected()) selected.add(e.getKey());
        }
        if (selected.isEmpty()) {
            errorLabel.setText("Select at least one order.");
            return;
        }
        startButton.setEnabled(false);
        try {
            created = deliveryDao.startRun(rider.id(), selected, staffId, staffName);
            dispose();
        } catch (DatabaseException e) {
            errorLabel.setText(e.getMessage());
            startButton.setEnabled(true);
        }
    }

    /** Shows the dialog and returns the created run, or null if cancelled. */
    static DeliveryRun show(Component parent, DeliveryDao deliveryDao, int staffId, String staffName) {
        StartDeliveryRunDialog dialog = new StartDeliveryRunDialog(parent, deliveryDao, staffId, staffName);
        dialog.setVisible(true);
        return dialog.created;
    }
}
