package rps.ui;

import rps.app.Session;
import rps.backup.OffsiteBackupService;
import rps.db.Db;
import rps.print.ReceiptPrinter;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.AppSettings;

import javax.print.PrintService;
import javax.swing.*;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class SettingsPanel extends JPanel {

    private final Session session;
    private final rps.db.OrderDao orderDao;
    private final rps.db.StaffDao staffDao;
    private final OffsiteBackupService offsiteBackupService;
    private final AppSettings settings = AppSettings.get();

    private final JTextField shopNameField = UiFactory.textField(24);
    private final JTextField shopAddressField = UiFactory.textField(24);
    private final JTextField shopPhoneField = UiFactory.textField(24);
    private final JTextField footerField = UiFactory.textField(24);

    private final JToggleButton width80Btn = UiFactory.chipToggle("80mm", true);
    private final JToggleButton width58Btn = UiFactory.chipToggle("58mm", false);
    private final JToggleButton textNormalBtn = UiFactory.chipToggle("Normal", false);
    private final JToggleButton textLargeBtn = UiFactory.chipToggle("Large", true);
    private final JToggleButton textXlBtn = UiFactory.chipToggle("Extra large", false);
    private final JComboBox<String> printerCombo = new JComboBox<>();
    private final JCheckBox silentPrintCheck = new JCheckBox("Print automatically when an order is confirmed");

    private final JLabel offsiteStatusLabel = UiFactory.muted("—");

    private final JToggleButton triggerPerOrderBtn = UiFactory.chipToggle("After every order", true);
    private final JToggleButton triggerIntervalBtn = UiFactory.chipToggle("Every N minutes", false);
    private final JTextField syncIntervalField = UiFactory.textField(6);
    private final JLabel offsiteConfigErrorLabel = UiFactory.errorText(" ");

    private final JTextField deliveryThresholdField = UiFactory.textField(8);
    private final JTextField deliveryFeeAmountField = UiFactory.textField(8);
    private final JLabel deliveryErrorLabel = UiFactory.errorText(" ");

    private final JTextField editWindowField = UiFactory.textField(6);
    private final JTextField cancelWindowField = UiFactory.textField(6);
    private final JLabel editWindowErrorLabel = UiFactory.errorText(" ");

    public SettingsPanel(Db db, Session session, OffsiteBackupService offsiteBackupService) {
        this.session = session;
        this.orderDao = new rps.db.OrderDao(db);
        this.staffDao = new rps.db.StaffDao(db);
        this.offsiteBackupService = offsiteBackupService;

        setLayout(new BorderLayout());
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        TouchScroll.install(content);
        content.add(UiFactory.title("Settings"));
        content.add(Box.createVerticalStrut(16));

        content.add(card("Shop Details (shown on receipts)", buildShopForm()));
        content.add(Box.createVerticalStrut(14));
        content.add(card("Delivery Fee", buildDeliveryForm()));
        content.add(Box.createVerticalStrut(14));
        content.add(card("Order Editing & Cancellation", buildOrderEditForm()));
        content.add(Box.createVerticalStrut(14));
        content.add(card("Receipt Printer", buildPrinterForm()));
        content.add(Box.createVerticalStrut(14));
        content.add(card("Backup (Backblaze B2)", buildOffsiteBackupForm()));
        content.add(Box.createVerticalStrut(14));
        content.add(card("Danger Zone", buildDangerZoneForm()));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        loadFromSettings();
        refreshPrinterList();
        refreshOffsiteStatus();
    }

    private JComponent card(String title, JComponent body) {
        rps.ui.theme.Card panel = new rps.ui.theme.Card(new BorderLayout(0, 10));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(720, Integer.MAX_VALUE));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        panel.add(UiFactory.heading(title), BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildShopForm() {
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(labeledField("Shop name", shopNameField));
        form.add(Box.createVerticalStrut(8));
        form.add(labeledField("Address", shopAddressField));
        form.add(Box.createVerticalStrut(8));
        form.add(labeledField("Phone", shopPhoneField));
        form.add(Box.createVerticalStrut(8));
        form.add(labeledField("Receipt footer message", footerField));
        form.add(Box.createVerticalStrut(10));
        JButton save = UiFactory.primaryButton("Save Shop Details");
        save.setAlignmentX(Component.LEFT_ALIGNMENT);
        save.addActionListener(e -> {
            settings.setShopName(shopNameField.getText().trim());
            settings.setShopAddress(shopAddressField.getText().trim());
            settings.setShopPhone(shopPhoneField.getText().trim());
            settings.setReceiptFooter(footerField.getText().trim());
            JOptionPane.showMessageDialog(this, "Saved.");
        });
        form.add(save);
        return form;
    }

    private JComponent buildDeliveryForm() {
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(UiFactory.muted("A Delivery order below the threshold picks up the fee automatically —"
            + " both are applied server-side when the order is confirmed."));
        form.add(Box.createVerticalStrut(10));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(labeledField("Minimum order (Rs)", deliveryThresholdField));
        row.add(labeledField("Delivery fee (Rs)", deliveryFeeAmountField));
        form.add(row);

        deliveryErrorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(deliveryErrorLabel);
        form.add(Box.createVerticalStrut(6));

        JButton save = UiFactory.primaryButton("Save Delivery Fee");
        save.setAlignmentX(Component.LEFT_ALIGNMENT);
        save.addActionListener(e -> {
            rps.util.Money threshold = rps.util.Validators.parsePrice(deliveryThresholdField.getText());
            rps.util.Money fee = rps.util.Validators.parsePrice(deliveryFeeAmountField.getText());
            if (threshold == null || fee == null) {
                deliveryErrorLabel.setText("Enter valid amounts (up to 2 decimals) for both fields.");
                return;
            }
            deliveryErrorLabel.setText(" ");
            settings.setDeliveryFeeThreshold(threshold.asBigDecimal().doubleValue());
            settings.setDeliveryFeeAmount(fee.asBigDecimal().doubleValue());
            JOptionPane.showMessageDialog(this, "Saved.");
        });
        form.add(save);
        return form;
    }

    private JComponent buildOrderEditForm() {
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(UiFactory.muted("<html><div style='width:520px'>From the Dashboard, any staff member"
            + " can edit a confirmed order — items, order type, discount — for the edit window after it"
            + " was placed, and can cancel it for the cancellation window. The two are separate on"
            + " purpose: changing an order and voiding it are different decisions. Past the"
            + " cancellation window an order can still be voided with Force Cancel, which is recorded"
            + " against whoever used it. Both windows apply whether or not the customer has"
            + " paid.</div></html>"));
        form.add(Box.createVerticalStrut(10));

        JPanel windows = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        windows.setOpaque(false);
        windows.setAlignmentX(Component.LEFT_ALIGNMENT);
        windows.add(labeledField("Edit window (minutes)", editWindowField));
        windows.add(labeledField("Cancellation window (minutes)", cancelWindowField));
        form.add(windows);
        editWindowErrorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(editWindowErrorLabel);
        form.add(Box.createVerticalStrut(6));

        JButton save = UiFactory.primaryButton("Save Windows");
        save.setAlignmentX(Component.LEFT_ALIGNMENT);
        save.addActionListener(e -> {
            Integer minutes = parsePositiveInt(editWindowField.getText());
            Integer cancelMinutes = parsePositiveInt(cancelWindowField.getText());
            if (minutes == null || cancelMinutes == null) {
                editWindowErrorLabel.setText("Enter a whole number of minutes (1 or more) in both fields.");
                return;
            }
            editWindowErrorLabel.setText(" ");
            settings.setOrderEditWindowMinutes(minutes);
            settings.setOrderCancelWindowMinutes(cancelMinutes);
            JOptionPane.showMessageDialog(this, "Saved.");
        });
        form.add(save);
        return form;
    }

    private static Integer parsePositiveInt(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            int v = Integer.parseInt(text.trim());
            return v >= 1 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JComponent buildPrinterForm() {
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel widthRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        widthRow.setOpaque(false);
        widthRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        ButtonGroup widthGroup = new ButtonGroup();
        widthGroup.add(width80Btn);
        widthGroup.add(width58Btn);
        widthRow.add(UiFactory.label("Paper width:"));
        widthRow.add(width80Btn);
        widthRow.add(width58Btn);
        form.add(widthRow);
        form.add(Box.createVerticalStrut(10));

        JPanel textRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        textRow.setOpaque(false);
        textRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        ButtonGroup textGroup = new ButtonGroup();
        textGroup.add(textNormalBtn);
        textGroup.add(textLargeBtn);
        textGroup.add(textXlBtn);
        textRow.add(UiFactory.label("Receipt text size:"));
        textRow.add(textNormalBtn);
        textRow.add(textLargeBtn);
        textRow.add(textXlBtn);
        form.add(textRow);
        form.add(Box.createVerticalStrut(6));
        form.add(UiFactory.muted("<html><div style='width:520px'>Bigger text means fewer characters"
            + " across the roll, so a long item name wraps onto more lines and the receipt is"
            + " taller — nothing is ever cut off. On an 80mm roll these print at roughly 7.25pt,"
            + " 8.25pt and 9.25pt. Use Preview Receipt to see the effect before"
            + " printing.</div></html>"));
        form.add(Box.createVerticalStrut(10));

        JPanel printerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        printerRow.setOpaque(false);
        printerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        printerRow.add(UiFactory.label("Printer:"));
        printerCombo.setPreferredSize(new Dimension(260, 30));
        printerRow.add(printerCombo);
        JButton refresh = UiFactory.secondaryButton("Refresh");
        refresh.addActionListener(e -> refreshPrinterList());
        printerRow.add(refresh);
        form.add(printerRow);
        form.add(Box.createVerticalStrut(6));
        form.add(UiFactory.muted("Auto-selects the system default if left blank — but never a"
            + " PDF/XPS/OneNote virtual printer, since those would prompt for a filename."));
        form.add(Box.createVerticalStrut(10));

        silentPrintCheck.setOpaque(false);
        silentPrintCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(silentPrintCheck);
        form.add(Box.createVerticalStrut(10));

        JButton save = UiFactory.primaryButton("Save Printer Settings");
        save.setAlignmentX(Component.LEFT_ALIGNMENT);
        save.addActionListener(e -> {
            settings.setReceiptPaperWidthMm(width58Btn.isSelected() ? 58 : 80);
            settings.setReceiptTextSize(
                textNormalBtn.isSelected() ? rps.print.RollSpec.TextSize.NORMAL
                : textXlBtn.isSelected() ? rps.print.RollSpec.TextSize.EXTRA_LARGE
                : rps.print.RollSpec.TextSize.LARGE);
            Object selected = printerCombo.getSelectedItem();
            settings.setPrinterName("Auto (system default)".equals(selected) ? "" : String.valueOf(selected));
            settings.setSilentPrintingEnabled(silentPrintCheck.isSelected());
            JOptionPane.showMessageDialog(this, "Saved.");
        });
        form.add(save);
        return form;
    }

    /** The B2 key/secret themselves are read-only here — set up once per install (baked
     *  into that client's installer config), not typed in from the till. Cadence (per-order
     *  vs. timed interval) and the interval length ARE editable here, since that's a real
     *  operational tradeoff (data-loss window vs. upload frequency) the shop may want to
     *  tune, not a one-time install secret. */
    private JComponent buildOffsiteBackupForm() {
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        offsiteStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(offsiteStatusLabel);
        form.add(Box.createVerticalStrut(10));

        JPanel triggerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        triggerRow.setOpaque(false);
        triggerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        ButtonGroup triggerGroup = new ButtonGroup();
        triggerGroup.add(triggerPerOrderBtn);
        triggerGroup.add(triggerIntervalBtn);
        triggerRow.add(UiFactory.label("Sync:"));
        triggerRow.add(triggerPerOrderBtn);
        triggerRow.add(triggerIntervalBtn);
        triggerRow.add(syncIntervalField);
        triggerRow.add(UiFactory.muted("minutes"));
        form.add(triggerRow);
        form.add(Box.createVerticalStrut(4));
        form.add(UiFactory.muted("After every order gives the strongest protection — at most one order"
            + " is ever at risk. A timed interval uploads less often but can lose that much recent work"
            + " if the till fails between syncs."));
        offsiteConfigErrorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(offsiteConfigErrorLabel);
        form.add(Box.createVerticalStrut(6));

        JButton saveCadence = UiFactory.secondaryButton("Save Backup Schedule");
        saveCadence.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveCadence.addActionListener(e -> {
            if (triggerIntervalBtn.isSelected()) {
                Integer minutes = parsePositiveInt(syncIntervalField.getText());
                if (minutes == null) {
                    offsiteConfigErrorLabel.setText("Enter a whole number of minutes (1 or more).");
                    return;
                }
                settings.setOffsiteSyncIntervalMinutes(minutes);
                settings.setOffsiteTriggerMode(OffsiteBackupService.TriggerMode.INTERVAL);
            } else {
                settings.setOffsiteTriggerMode(OffsiteBackupService.TriggerMode.PER_ORDER);
            }
            offsiteConfigErrorLabel.setText(" ");
            offsiteBackupService.reschedule();
            refreshOffsiteStatus();
            JOptionPane.showMessageDialog(this, "Saved.");
        });
        form.add(saveCadence);
        form.add(Box.createVerticalStrut(10));

        JButton syncNow = UiFactory.primaryButton("Sync Now");
        syncNow.setAlignmentX(Component.LEFT_ALIGNMENT);
        syncNow.setEnabled(offsiteBackupService.isConfigured());
        syncNow.addActionListener(e -> {
            syncNow.setEnabled(false);
            Busy.call(this, () -> {
                    offsiteBackupService.syncNowBlocking();
                    return null;
                })
                .message("Syncing to backup…")
                .onSuccess(v -> {
                    syncNow.setEnabled(true);
                    refreshOffsiteStatus();
                    JOptionPane.showMessageDialog(this, "Backup complete.");
                })
                .onError(e2 -> {
                    syncNow.setEnabled(true);
                    JOptionPane.showMessageDialog(this, "Backup failed: " + e2.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                })
                .start();
        });
        form.add(syncNow);
        return form;
    }

    private void refreshOffsiteStatus() {
        if (!offsiteBackupService.isConfigured()) {
            offsiteStatusLabel.setText("Not configured for this install.");
            offsiteStatusLabel.setForeground(Theme.TEXT_MUTED);
            return;
        }
        if (offsiteBackupService.isUnavailable()) {
            offsiteStatusLabel.setText("Backups are disabled: " + offsiteBackupService.unavailableReason());
            offsiteStatusLabel.setForeground(Theme.STATUS_CANCELLED);
            return;
        }
        java.time.Instant last = settings.lastOffsiteSyncTime();
        String cadence = settings.offsiteTriggerMode() == OffsiteBackupService.TriggerMode.PER_ORDER
            ? "after every order"
            : "every " + settings.offsiteSyncIntervalMinutes() + " minutes";
        if (last == null) {
            offsiteStatusLabel.setText("No backup taken yet — runs automatically " + cadence + ".");
        } else {
            String when = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
                .withZone(ZoneId.systemDefault()).format(last);
            offsiteStatusLabel.setText("Last backup: " + when + "  (runs " + cadence + ")");
        }
        offsiteStatusLabel.setForeground(Theme.TEXT_MUTED);
    }

    private void refreshPrinterList() {
        printerCombo.removeAllItems();
        printerCombo.addItem("Auto (system default)");
        List<PrintService> services = ReceiptPrinter.listCandidatePrinters();
        for (PrintService s : services) {
            printerCombo.addItem(s.getName());
        }
        String configured = settings.printerName();
        if (configured != null && !configured.isBlank()) {
            printerCombo.setSelectedItem(configured);
        }
    }

    private void loadFromSettings() {
        shopNameField.setText(settings.shopName());
        shopAddressField.setText(settings.shopAddress());
        shopPhoneField.setText(settings.shopPhone());
        footerField.setText(settings.receiptFooter());
        deliveryThresholdField.setText(rps.util.Money.fromDouble(settings.deliveryFeeThreshold()).asBigDecimal().toPlainString());
        deliveryFeeAmountField.setText(rps.util.Money.fromDouble(settings.deliveryFeeAmount()).asBigDecimal().toPlainString());
        editWindowField.setText(Integer.toString(settings.orderEditWindowMinutes()));
        cancelWindowField.setText(Integer.toString(settings.orderCancelWindowMinutes()));
        boolean is58 = settings.receiptPaperWidthMm() < 70;
        width58Btn.setSelected(is58);
        width80Btn.setSelected(!is58);
        switch (settings.receiptTextSize()) {
            case NORMAL -> textNormalBtn.setSelected(true);
            case LARGE -> textLargeBtn.setSelected(true);
            case EXTRA_LARGE -> textXlBtn.setSelected(true);
        }
        silentPrintCheck.setSelected(settings.isSilentPrintingEnabled());
        boolean interval = settings.offsiteTriggerMode() == OffsiteBackupService.TriggerMode.INTERVAL;
        triggerIntervalBtn.setSelected(interval);
        triggerPerOrderBtn.setSelected(!interval);
        syncIntervalField.setText(Integer.toString(settings.offsiteSyncIntervalMinutes()));
    }

    private JComponent buildDangerZoneForm() {
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(UiFactory.muted("<html><div style='width:520px'>Permanently deletes every"
            + " order, delivery run and rider — the entire sales history and till record."
            + " The menu and staff accounts are NOT affected. There is no undo; take a"
            + " backup first if this data might be needed again.</div></html>"));
        form.add(Box.createVerticalStrut(10));

        JButton reset = new JButton("Reset All Data…");
        reset.setFont(Theme.FONT_BODY_BOLD);
        reset.setForeground(Theme.STATUS_CANCELLED);
        reset.setBackground(Theme.SURFACE);
        reset.setFocusPainted(false);
        reset.setMargin(new Insets(9, 19, 9, 19));
        reset.setBorder(BorderFactory.createLineBorder(Theme.STATUS_CANCELLED, 1, true));
        reset.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        reset.setAlignmentX(Component.LEFT_ALIGNMENT);
        reset.addActionListener(e -> confirmAndResetAllData());
        form.add(reset);
        return form;
    }

    /**
     * Three separate, escalating confirmations before a single irreversible SQL
     * statement runs — deliberately more friction than anything else in this app,
     * because nothing else in this app deletes the entire order history in one action:
     *   1. A plain confirm that spells out exactly what is and is not affected.
     *   2. Re-entering the signed-in manager's own password — proves this is the
     *      account holder acting deliberately, not whoever is standing at an
     *      already-unlocked till.
     *   3. Typing the literal word RESET — the same pattern GitHub uses before deleting
     *      a repository, so a reflexive third click of "Yes" can't slip through.
     * Any cancellation, wrong password, or mismatched text at any step aborts with
     * nothing changed — resetAllTradingData() only ever runs after all three pass.
     */
    private void confirmAndResetAllData() {
        int step1 = JOptionPane.showConfirmDialog(this,
            "This permanently deletes every order, delivery run and rider —\n"
                + "the entire sales history and till record.\n\n"
                + "The menu and staff accounts are NOT affected.\n\n"
                + "This cannot be undone. Continue?",
            "Reset All Data — Step 1 of 3", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (step1 != JOptionPane.YES_OPTION) return;

        JPasswordField passwordField = new JPasswordField();
        int step2 = JOptionPane.showConfirmDialog(this,
            new Object[]{"Step 2 of 3 — confirm it's you.",
                "Re-enter your password (" + session.staffName() + "):", passwordField},
            "Reset All Data — Step 2 of 3", JOptionPane.OK_CANCEL_OPTION);
        if (step2 != JOptionPane.OK_OPTION) return;
        char[] pw = passwordField.getPassword();
        String password = new String(pw);
        java.util.Arrays.fill(pw, '\0');
        boolean authenticated;
        try {
            authenticated = staffDao.authenticate(session.staffId(), password) != null;
        } catch (rps.db.DatabaseException e) {
            JOptionPane.showMessageDialog(this, "Could not verify your password: " + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!authenticated) {
            JOptionPane.showMessageDialog(this, "Incorrect password. Reset cancelled.",
                "Reset All Data", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String typed = JOptionPane.showInputDialog(this,
            "Step 3 of 3 — type RESET (in capitals) to permanently delete this data:",
            "Reset All Data — Step 3 of 3", JOptionPane.WARNING_MESSAGE);
        if (!"RESET".equals(typed)) {
            if (typed != null && !typed.isBlank()) {
                JOptionPane.showMessageDialog(this, "Text did not match \"RESET\" exactly. Reset cancelled.");
            }
            return;
        }

        try {
            orderDao.resetAllTradingData();
            JOptionPane.showMessageDialog(this,
                "All order and delivery data has been reset. The menu and staff accounts were not touched.",
                "Reset complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (rps.db.DatabaseException e) {
            JOptionPane.showMessageDialog(this, "Reset failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static JComponent labeledField(String labelText, JTextField field) {
        JPanel wrap = new JPanel(new BorderLayout(0, 4));
        wrap.setOpaque(false);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(UiFactory.muted(labelText), BorderLayout.NORTH);
        wrap.add(field, BorderLayout.CENTER);
        return wrap;
    }
}
