package rps.ui;

import rps.app.Session;
import rps.db.Db;
import rps.db.DatabaseException;
import rps.db.MenuDao;
import rps.db.OrderDao;
import rps.model.DiscountMode;
import rps.model.DraftLine;
import rps.model.MenuItem;
import rps.model.MenuItemVariant;
import rps.model.Order;
import rps.model.OrderDraft;
import rps.model.OrderLine;
import rps.model.OrderType;
import rps.print.ReceiptPrinter;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.Money;
import rps.util.Validators;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lets a cashier change an already-confirmed order — items, order type, discount, cash —
 * within the configurable edit window (Settings, default 15 minutes). Reuses OrderDraft
 * exactly as PosPanel does: the order's current state is loaded into a draft, edited the
 * same way a new order is built, and saved through OrderDao.updateOrder, which re-derives
 * every total server-side in one SQL statement rather than adjusting the old figures —
 * the same reason PosPanel never trusts its own client-side totals at Confirm.
 */
final class OrderEditDialog extends JDialog {

    private final Db db;
    private final Session session;
    private final MenuDao menuDao;
    private final OrderDao orderDao;
    private final ReceiptPrinter receiptPrinter;
    private final Runnable onSaved;
    private final long orderId;

    private final OrderDraft draft = new OrderDraft();
    private List<MenuItem> allItems = List.of();
    private List<rps.model.Category> categories = List.of();

    private final JTextField itemSearchField = UiFactory.textField(20);
    private final DefaultListModel<MenuItem> itemListModel = new DefaultListModel<>();
    private final JList<MenuItem> itemList = new JList<>(itemListModel);

    private final JPanel ticketList = new JPanel();
    private final JLabel emptyTicketLabel = emptyStateLabel();

    private final JTextField tableField = UiFactory.textField(8);
    private final JComponent tableRow = labeledField("Table number *", tableField);
    private final JTextField phoneField = UiFactory.textField(16);
    private final JLabel phoneLabel = UiFactory.muted("Phone number *");
    private final JComponent phoneRow = labeledFieldWithLabel(phoneLabel, phoneField);
    private final JTextField addressField = UiFactory.textField(24);
    private final JComponent addressRow = labeledField("Delivery address *", addressField);
    private final JLabel deliveryFeeLabel = UiFactory.muted(" ");

    private final JTextField notesField = UiFactory.textField(24);
    private final JComponent notesRow = labeledField("Order note (optional)", notesField);

    private final JToggleButton discountNoneBtn = UiFactory.chipToggle("No discount", true);
    private final JToggleButton discountPercentBtn = UiFactory.chipToggle("%", false);
    private final JToggleButton discountAmountBtn = UiFactory.chipToggle("Rs off", false);
    private final JTextField discountValueField = UiFactory.textField(8);
    private final JComponent discountValueRow = labeledField("Discount value", discountValueField);
    private final JLabel discountAmountLabel = UiFactory.muted(" ");

    private final JLabel paidSoFarLabel = UiFactory.muted(" ");
    private final JTextField cashTenderedField = UiFactory.textField(10);
    private final JLabel changeDueLabel = UiFactory.labelBold(" ");
    /** amount_paid as it stood when this dialog opened — cashTenderedField now means an
     *  ADDITIONAL payment collected during this edit (see OrderDao#applyPayment), not a
     *  restatement of the full amount, so it must never be pre-filled from this. */
    private Money originalAmountPaid = Money.ZERO;

    private final JLabel totalLabel = totalLabelStyled();
    private final JLabel fieldsError = UiFactory.errorText(" ");
    private final JButton saveButton = UiFactory.primaryButton("Save Changes");
    /** Same guard as PosPanel.submitting — validateFields()/refreshTotals() re-enable
     *  saveButton, so disabling it in the click handler alone let a ticket change during
     *  an in-flight save re-enable it and submit the edit twice. */
    private boolean submitting = false;

    OrderEditDialog(Component parent, Db db, Session session, Order order, ReceiptPrinter receiptPrinter, Runnable onSaved) {
        super(SwingUtilities.getWindowAncestor(parent), "Edit Order " + order.orderNumber(),
            ModalityType.APPLICATION_MODAL);
        this.db = db;
        this.session = session;
        this.menuDao = new MenuDao(db);
        this.orderDao = new OrderDao(db);
        this.receiptPrinter = receiptPrinter;
        this.onSaved = onSaved;
        this.orderId = order.id();

        populateDraftFrom(order);

        getContentPane().setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildItemPicker(), buildTicketPanel());
        split.setResizeWeight(0.45);
        split.setDividerSize(1);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        loadMenu();
        refreshTicket();
        updateConditionalFields();

        setPreferredSize(new Dimension(920, 640));
        pack();
        setLocationRelativeTo(parent);
    }

    private void populateDraftFrom(Order order) {
        draft.setType(order.type());
        draft.setTableNumber(order.tableNumber());
        draft.setCustomerName(order.customerName());
        draft.setCustomerPhone(order.customerPhone());
        draft.setDeliveryAddress(order.deliveryAddress());
        draft.setNotes(order.notes());
        if (order.discountMode() == DiscountMode.PERCENT) {
            draft.setDiscount(DiscountMode.PERCENT, order.discountRate());
        } else if (order.discountMode() == DiscountMode.AMOUNT) {
            // AMOUNT mode only ever persists the final clamped discount_total, not the
            // raw value the cashier originally typed — that was never stored — so the
            // applied amount is the honest starting point for editing.
            draft.setDiscount(DiscountMode.AMOUNT, order.totals().discountTotal().asBigDecimal());
        }
        // cashTendered stays null here — it now means an additional payment collected
        // during THIS edit, and must start empty so an untouched field doesn't get
        // re-applied as a second payment on top of what's already recorded.
        this.originalAmountPaid = order.totals().amountPaid();
        for (OrderLine line : order.lines()) {
            if (line.variantId() == null) continue;
            draft.addLine(new DraftLine(line.variantId(), line.displayName(), line.unitPrice(),
                line.quantity(), line.notes(), line.optionValueId(), line.optionGroupName(), line.optionValueName()));
        }

        tableField.setText(order.tableNumber() == null ? "" : order.tableNumber());
        phoneField.setText(order.customerPhone() == null ? "" : order.customerPhone());
        addressField.setText(order.deliveryAddress() == null ? "" : order.deliveryAddress());
        notesField.setText(order.notes() == null ? "" : order.notes());
        if (draft.discountMode() == DiscountMode.PERCENT) {
            discountPercentBtn.setSelected(true);
            discountValueField.setText(order.discountRate() == null ? "" : order.discountRate().stripTrailingZeros().toPlainString());
        } else if (draft.discountMode() == DiscountMode.AMOUNT) {
            discountAmountBtn.setSelected(true);
            discountValueField.setText(order.totals().discountTotal().asBigDecimal().toPlainString());
        } else {
            discountNoneBtn.setSelected(true);
        }
        discountValueRow.setVisible(draft.discountMode() != DiscountMode.NONE);
        paidSoFarLabel.setText("Paid so far: " + originalAmountPaid.format() + " of " + order.totals().total().format());
    }

    // -------------------------------------------------------------- left: item picker

    private JComponent buildItemPicker() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(Theme.SURFACE);
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        panel.add(UiFactory.heading("Add items"), BorderLayout.NORTH);

        itemSearchField.putClientProperty("JTextField.placeholderText", "Search menu…");
        itemSearchField.getDocument().addDocumentListener((SimpleDocListener) this::filterItems);

        itemList.setFont(Theme.FONT_BODY);
        itemList.setCellRenderer((list, item, index, isSelected, hasFocus) -> {
            String priceText = item.sized() ? "from " + item.lowestPrice().format() : item.singlePrice().format();
            JLabel l = new JLabel(item.name() + "   " + priceText);
            l.setFont(Theme.FONT_BODY);
            l.setOpaque(true);
            l.setBackground(isSelected ? Theme.PRIMARY_TINT : Theme.SURFACE);
            l.setForeground(Theme.TEXT);
            l.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            return l;
        });
        itemList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    MenuItem item = itemList.getSelectedValue();
                    if (item != null) onItemPicked(item);
                }
            }
        });

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(itemSearchField, BorderLayout.NORTH);
        top.add(new JScrollPane(itemList), BorderLayout.CENTER);
        panel.add(top, BorderLayout.CENTER);

        JLabel hint = UiFactory.muted("Double-click an item to add it to the order.");
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private void loadMenu() {
        try {
            categories = menuDao.listCategories(true);
            allItems = menuDao.listAvailableItems();
            filterItems();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, "Could not load the menu: " + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void filterItems() {
        String query = itemSearchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        itemListModel.clear();
        for (MenuItem item : allItems) {
            if (item.variants().isEmpty()) continue;
            if (query.isEmpty() || item.name().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                itemListModel.addElement(item);
            }
        }
    }

    private String categoryNameFor(MenuItem item) {
        for (rps.model.Category c : categories) {
            if (c.id() == item.categoryId()) return c.name();
        }
        return "";
    }

    private void onItemPicked(MenuItem item) {
        MenuItemVariant chosen;
        rps.model.OptionValue chosenOption = null;
        if ((item.sized() && item.variants().size() > 1) || item.hasOptions()) {
            SizePickerDialog.Selection selection =
                SizePickerDialog.pick(this, item, rps.ui.icon.LineIcon.forCategory(categoryNameFor(item)));
            if (selection == null) return;
            chosen = selection.variant();
            chosenOption = selection.option();
        } else {
            chosen = item.variants().get(0);
        }

        Integer chosenOptionId = chosenOption == null ? null : chosenOption.id();
        for (int i = 0; i < draft.lines().size(); i++) {
            DraftLine existing = draft.lines().get(i);
            if (existing.variantId() == chosen.id() && java.util.Objects.equals(existing.optionValueId(), chosenOptionId)) {
                draft.updateQuantity(i, existing.quantity() + 1);
                refreshTicket();
                return;
            }
        }
        String displayName = chosen.hasSize() ? item.name() + " (" + chosen.sizeLabel() + ")" : item.name();
        String optionGroupName = chosenOption == null ? null : item.optionGroup().name();
        String optionValueName = chosenOption == null ? null : chosenOption.name();
        draft.addLine(new DraftLine(chosen.id(), displayName, chosen.price(), 1, null,
            chosenOptionId, optionGroupName, optionValueName));
        refreshTicket();
    }

    // -------------------------------------------------------------- right: ticket + details

    private JComponent buildTicketPanel() {
        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Theme.SURFACE);
        right.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER));

        ticketList.setOpaque(false);
        ticketList.setLayout(new BoxLayout(ticketList, BoxLayout.Y_AXIS));
        ticketList.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JPanel details = buildDetailsSection();

        ScrollableColumn column = new ScrollableColumn(null);
        column.setOpaque(true);
        column.setBackground(Theme.SURFACE);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.add(ticketList);
        column.add(details);

        JScrollPane scroll = new JScrollPane(column,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        right.add(scroll, BorderLayout.CENTER);
        right.add(buildButtons(), BorderLayout.SOUTH);
        return right;
    }

    private JPanel buildDetailsSection() {
        JPanel panel = new JPanel();
        panel.setOpaque(true);
        panel.setBackground(Theme.SURFACE);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
            BorderFactory.createEmptyBorder(16, 16, 16, 16)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(sectionLabel("ORDER TYPE"));
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildOrderTypeChips());
        panel.add(Box.createVerticalStrut(16));

        panel.add(tableRow);
        tableField.getDocument().addDocumentListener((SimpleDocListener) this::validateFields);
        panel.add(Box.createVerticalStrut(10));
        panel.add(phoneRow);
        phoneField.getDocument().addDocumentListener((SimpleDocListener) this::validateFields);
        panel.add(Box.createVerticalStrut(10));
        addressRow.setVisible(false);
        panel.add(addressRow);
        addressField.getDocument().addDocumentListener((SimpleDocListener) this::validateFields);

        deliveryFeeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        deliveryFeeLabel.setVisible(false);
        panel.add(deliveryFeeLabel);

        fieldsError.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(fieldsError);
        panel.add(Box.createVerticalStrut(14));
        panel.add(divider());
        panel.add(Box.createVerticalStrut(14));

        panel.add(notesRow);
        notesField.getDocument().addDocumentListener((SimpleDocListener) () -> draft.setNotes(notesField.getText()));

        panel.add(Box.createVerticalStrut(14));
        panel.add(divider());
        panel.add(Box.createVerticalStrut(14));

        panel.add(sectionLabel("DISCOUNT"));
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildDiscountChips());
        panel.add(Box.createVerticalStrut(8));
        panel.add(discountValueRow);
        discountAmountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(discountAmountLabel);
        discountValueField.getDocument().addDocumentListener((SimpleDocListener) this::onDiscountChanged);

        panel.add(Box.createVerticalStrut(14));
        panel.add(divider());
        panel.add(Box.createVerticalStrut(14));

        panel.add(sectionLabel("CASH"));
        panel.add(Box.createVerticalStrut(8));
        paidSoFarLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(paidSoFarLabel);
        panel.add(Box.createVerticalStrut(6));
        panel.add(labeledField("Additional payment now (optional)", cashTenderedField));
        cashTenderedField.getDocument().addDocumentListener((SimpleDocListener) this::onCashChanged);
        panel.add(Box.createVerticalStrut(6));
        changeDueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(changeDueLabel);

        panel.add(Box.createVerticalStrut(14));
        panel.add(divider());
        panel.add(Box.createVerticalStrut(14));

        JPanel totalRow = new JPanel(new BorderLayout());
        totalRow.setOpaque(false);
        totalRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        totalRow.add(UiFactory.heading("Total"), BorderLayout.WEST);
        totalRow.add(totalLabel, BorderLayout.EAST);
        panel.add(totalRow);

        return panel;
    }

    private JComponent buildOrderTypeChips() {
        JPanel row = new JPanel(new GridLayout(1, OrderType.values().length, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        ButtonGroup group = new ButtonGroup();
        for (OrderType type : OrderType.values()) {
            JToggleButton btn = UiFactory.chipToggle(type.label(), type == draft.type());
            btn.addActionListener(e -> {
                draft.setType(type);
                updateConditionalFields();
            });
            group.add(btn);
            row.add(btn);
        }
        return row;
    }

    private JComponent buildDiscountChips() {
        JPanel row = new JPanel(new GridLayout(1, 3, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        ButtonGroup group = new ButtonGroup();
        discountNoneBtn.addActionListener(e -> setDiscountMode(DiscountMode.NONE));
        discountPercentBtn.addActionListener(e -> setDiscountMode(DiscountMode.PERCENT));
        discountAmountBtn.addActionListener(e -> setDiscountMode(DiscountMode.AMOUNT));
        group.add(discountNoneBtn);
        group.add(discountPercentBtn);
        group.add(discountAmountBtn);
        row.add(discountNoneBtn);
        row.add(discountPercentBtn);
        row.add(discountAmountBtn);
        return row;
    }

    private JComponent buildButtons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        panel.setBackground(Theme.SURFACE);
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        JButton cancel = UiFactory.secondaryButton("Cancel");
        cancel.addActionListener(e -> dispose());
        saveButton.addActionListener(e -> save());
        panel.add(cancel);
        panel.add(saveButton);
        getRootPane().setDefaultButton(saveButton);
        return panel;
    }

    // -------------------------------------------------------------- ticket rows

    private void refreshTicket() {
        ticketList.removeAll();
        if (draft.isEmpty()) {
            ticketList.add(emptyTicketLabel);
        } else {
            for (int i = 0; i < draft.lines().size(); i++) {
                ticketList.add(buildTicketRow(i, draft.lines().get(i)));
            }
        }
        ticketList.revalidate();
        ticketList.repaint();
        refreshTotals();
    }

    private JComponent buildTicketRow(int index, DraftLine line) {
        boolean hasOption = line.optionValueName() != null && !line.optionValueName().isBlank();
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(true);
        row.setBackground(Theme.SURFACE);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, hasOption ? 92 : 74));
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
            BorderFactory.createEmptyBorder(12, 4, 12, 4)));

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.add(UiFactory.labelBold(line.displayName()));
        info.add(UiFactory.muted(line.unitPrice().format() + " each"));
        if (hasOption) {
            info.add(UiFactory.muted(line.optionGroupName() + ": " + line.optionValueName()));
        }
        row.add(info, BorderLayout.CENTER);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));

        JPanel stepper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        stepper.setOpaque(false);
        JButton minus = UiFactory.stepperButton("−");
        JLabel qty = new JLabel(String.valueOf(line.quantity()), SwingConstants.CENTER);
        qty.setFont(Theme.FONT_BODY_BOLD);
        qty.setPreferredSize(new Dimension(26, 26));
        JButton plus = UiFactory.stepperButton("+");
        JButton remove = UiFactory.stepperButton("×");
        remove.setForeground(Theme.STATUS_CANCELLED);
        minus.addActionListener(e -> adjustLine(index, -1));
        plus.addActionListener(e -> adjustLine(index, 1));
        remove.addActionListener(e -> removeLine(index));
        stepper.add(minus);
        stepper.add(qty);
        stepper.add(plus);
        stepper.add(remove);
        stepper.setAlignmentX(Component.RIGHT_ALIGNMENT);
        right.add(stepper);

        JLabel lineTotal = UiFactory.labelBold(line.lineTotal().format());
        lineTotal.setForeground(Theme.PRIMARY);
        lineTotal.setAlignmentX(Component.RIGHT_ALIGNMENT);
        lineTotal.setHorizontalAlignment(SwingConstants.RIGHT);
        right.add(Box.createVerticalStrut(4));
        right.add(lineTotal);

        row.add(right, BorderLayout.EAST);
        return row;
    }

    private void adjustLine(int index, int delta) {
        int newQty = draft.lines().get(index).quantity() + delta;
        if (newQty <= 0) {
            draft.removeLine(index);
        } else {
            draft.updateQuantity(index, newQty);
        }
        refreshTicket();
    }

    private void removeLine(int index) {
        draft.removeLine(index);
        refreshTicket();
    }

    // -------------------------------------------------------------- discount / cash / totals

    private void setDiscountMode(DiscountMode mode) {
        discountValueRow.setVisible(mode != DiscountMode.NONE);
        if (mode == DiscountMode.NONE) {
            discountValueField.setText("");
        }
        applyDiscountFromFields(mode);
        revalidate();
        repaint();
    }

    private void onDiscountChanged() {
        DiscountMode mode = discountPercentBtn.isSelected() ? DiscountMode.PERCENT
            : discountAmountBtn.isSelected() ? DiscountMode.AMOUNT : DiscountMode.NONE;
        applyDiscountFromFields(mode);
    }

    private void applyDiscountFromFields(DiscountMode mode) {
        if (mode == DiscountMode.NONE) {
            draft.setDiscount(DiscountMode.NONE, null);
        } else {
            BigDecimal value = parsePositiveDecimal(discountValueField.getText());
            boolean valid = value != null && (mode != DiscountMode.PERCENT
                || value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.valueOf(100)) <= 0);
            if (valid && mode == DiscountMode.AMOUNT) {
                Money subtotal = draft.estimatedSubtotal();
                if (Money.of(value).compareTo(subtotal) > 0) {
                    value = subtotal.asBigDecimal();
                }
            }
            UiFactory.markValid(discountValueField);
            draft.setDiscount(mode, valid ? value : null);
            if (!valid && !discountValueField.getText().isBlank()) {
                UiFactory.markInvalid(discountValueField);
            }
        }
        refreshTotals();
    }

    private static BigDecimal parsePositiveDecimal(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            BigDecimal v = new BigDecimal(text.trim());
            return v.signum() < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void onCashChanged() {
        Money value = Validators.parsePrice(cashTenderedField.getText());
        if (value == null && !cashTenderedField.getText().isBlank()) {
            UiFactory.markInvalid(cashTenderedField);
        } else {
            UiFactory.markValid(cashTenderedField);
        }
        draft.setCashTendered(value);
        refreshTotals();
    }

    private void updateConditionalFields() {
        boolean isDineIn = draft.type() == OrderType.DINE_IN;
        boolean isDelivery = draft.type() == OrderType.DELIVERY;
        tableRow.setVisible(isDineIn);
        addressRow.setVisible(isDelivery);
        phoneLabel.setText(isDineIn ? "Phone number (optional)" : "Phone number *");
        validateFields();
        refreshTotals();
        revalidate();
        repaint();
    }

    private void validateFields() {
        draft.setCustomerPhone(phoneField.getText());
        draft.setDeliveryAddress(addressField.getText());
        draft.setTableNumber(tableField.getText());

        boolean isDineIn = draft.type() == OrderType.DINE_IN;
        boolean isDelivery = draft.type() == OrderType.DELIVERY;
        boolean phoneBlank = phoneField.getText().isBlank();
        boolean phoneOk = Validators.isValidPakistaniPhone(phoneField.getText());
        boolean phoneRequired = !isDineIn;

        if (phoneBlank || phoneOk) UiFactory.markValid(phoneField);
        else UiFactory.markInvalid(phoneField);

        if (isDineIn) {
            boolean tableBlank = tableField.getText().isBlank();
            if (tableBlank) UiFactory.markInvalid(tableField); else UiFactory.markValid(tableField);
        }

        String error = " ";
        if (isDineIn && tableField.getText().isBlank()) {
            error = "Table number is required.";
        } else if (phoneRequired && phoneBlank) {
            error = "Phone number is required.";
        } else if (!phoneBlank && !phoneOk) {
            error = "Enter a valid Pakistani mobile number (03XXXXXXXXX).";
        } else if (isDelivery) {
            boolean addrOk = Validators.isValidAddress(addressField.getText());
            if (addressField.getText().isBlank() || addrOk) UiFactory.markValid(addressField);
            else UiFactory.markInvalid(addressField);

            if (addressField.getText().isBlank()) error = "Delivery address is required.";
            else if (!addrOk) error = "Address must be at least 10 characters.";
        }
        fieldsError.setText(error);
        saveButton.setEnabled(!submitting && draft.canConfirm());
    }

    private void refreshTotals() {
        totalLabel.setText(draft.estimatedTotal().format());
        Money discount = draft.estimatedDiscount();
        discountAmountLabel.setText(discount.isZero() ? " " : "Discount: -" + discount.format());

        Money deliveryFee = draft.estimatedDeliveryFee();
        deliveryFeeLabel.setVisible(!deliveryFee.isZero());
        if (!deliveryFee.isZero()) {
            deliveryFeeLabel.setText("Delivery fee: +" + deliveryFee.format());
        }

        // cashTenderedField is an ADDITIONAL payment on top of what was already paid
        // (originalAmountPaid) — never a restatement of the full total — so "change due"
        // / "balance due" here is computed against what's still owed BEFORE this
        // payment, not against the full order total.
        Money remainingBefore = draft.estimatedTotal().subtractClamped(originalAmountPaid);
        if (draft.cashTendered() == null) {
            changeDueLabel.setText(remainingBefore.isZero() ? "Fully paid" : "Balance due: " + remainingBefore.format());
            changeDueLabel.setForeground(remainingBefore.isZero() ? Theme.STATUS_COMPLETED : Theme.STATUS_CANCELLED);
        } else {
            boolean enough = draft.cashTendered().compareTo(remainingBefore) >= 0;
            Money amount = enough
                ? draft.cashTendered().subtractClamped(remainingBefore)
                : remainingBefore.subtract(draft.cashTendered());
            changeDueLabel.setText((enough ? "Change due: " : "Balance due: ") + amount.format());
            changeDueLabel.setForeground(enough ? Theme.STATUS_COMPLETED : Theme.STATUS_CANCELLED);
        }
        saveButton.setEnabled(!submitting && draft.canConfirm());
    }

    // -------------------------------------------------------------- save

    private void save() {
        if (submitting) return;
        submitting = true;
        saveButton.setEnabled(false);
        OrderDraft confirmed = draft.snapshot();   // isolate from edits made mid-save
        Busy.call(saveButton, () -> orderDao.updateOrder(orderId, confirmed, session.staffId(), session.staffName()))
            .message("Saving changes…")
            .onSuccess(saved -> {
                submitting = false;
                receiptPrinter.printBothAsync(saved);
                onSaved.run();
                dispose();
            })
            .onError(e -> {
                submitting = false;
                JOptionPane.showMessageDialog(this, e.getMessage(), "Could not save changes", JOptionPane.ERROR_MESSAGE);
                saveButton.setEnabled(true);
            })
            .start();
    }

    // -------------------------------------------------------------- small helpers

    private static JLabel totalLabelStyled() {
        JLabel l = UiFactory.title("Rs 0.00");
        l.setForeground(Theme.PRIMARY);
        return l;
    }

    private static JLabel emptyStateLabel() {
        JLabel l = UiFactory.muted("Add at least one item.");
        l.setBorder(BorderFactory.createEmptyBorder(24, 4, 24, 4));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent divider() {
        JPanel divider = new JPanel();
        divider.setPreferredSize(new Dimension(1, 1));
        divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        divider.setBackground(Theme.BORDER);
        divider.setOpaque(true);
        divider.setAlignmentX(Component.LEFT_ALIGNMENT);
        return divider;
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = UiFactory.muted(text);
        l.setFont(Theme.FONT_SMALL_BOLD);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent labeledField(String labelText, JTextField field) {
        return labeledFieldWithLabel(UiFactory.muted(labelText), field);
    }

    private static JComponent labeledFieldWithLabel(JLabel label, JTextField field) {
        JPanel wrap = new JPanel(new BorderLayout(0, 6));
        wrap.setOpaque(false);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        wrap.add(label, BorderLayout.NORTH);
        wrap.add(field, BorderLayout.CENTER);
        return wrap;
    }

    @FunctionalInterface
    private interface SimpleDocListener extends javax.swing.event.DocumentListener {
        void onChange();
        @Override default void insertUpdate(javax.swing.event.DocumentEvent e) { onChange(); }
        @Override default void removeUpdate(javax.swing.event.DocumentEvent e) { onChange(); }
        @Override default void changedUpdate(javax.swing.event.DocumentEvent e) { onChange(); }
    }
}
