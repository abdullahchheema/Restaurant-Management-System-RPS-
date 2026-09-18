package rps.ui;

import rps.db.DatabaseException;
import rps.db.MenuDao;
import rps.model.Category;
import rps.model.MenuItem;
import rps.model.MenuItemVariant;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.Money;
import rps.util.Validators;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Add or edit a menu item: category, name, description, and size/price rows.
 *
 * The size/price rows are plain labeled JTextFields in a column, not a JTable. A JTable
 * defaults its own preferred size from its column count (75px/column, no explicit widths
 * set here) rather than the JScrollPane wrapping it, and that mismatch made the price
 * column collapse to an unreadable sliver in practice — reported as "the price doesn't
 * even show". Plain fields use the exact same styling (UiFactory.textField) already
 * proven reliable everywhere else in this app (login, POS, staff forms), so there is no
 * separate layout system to get wrong for this one dialog.
 */
final class MenuItemEditorDialog extends JDialog {

    private final MenuDao menuDao;
    private final MenuItem editing; // null when adding
    private final Runnable onSaved;

    private final JComboBox<Category> categoryCombo;
    private final JComboBox<Object> optionGroupCombo;
    private static final String NO_OPTION_GROUP = "None";
    private final JTextField nameField = UiFactory.textField(24);
    private final JTextField descField = UiFactory.textField(24);
    private final JCheckBox sizedCheck = new JCheckBox("This item comes in sizes");
    private final JLabel errorLabel = UiFactory.errorText(" ");

    /** Shown when NOT sized: exactly one price field, no label. */
    private final JTextField singlePriceField = UiFactory.textField(10);
    private final JPanel singlePriceRow = new JPanel(new BorderLayout(8, 0));

    /** Shown when sized: one (label, price, remove) row per size. */
    private final JPanel sizeColumnHeaders = buildSizeColumnHeaders();
    private final JPanel sizeRowsPanel = new JPanel();
    private final List<SizeRow> sizeRows = new ArrayList<>();
    private final JLabel sizeEmptyHint = UiFactory.muted("Pick a size below, or add a custom one.");
    private final JPanel presetPalette = buildPresetPalette();
    private final JButton customSizeButton = UiFactory.secondaryButton("+ Custom size");

    private record SizeRow(JPanel row, JTextField label, JTextField price) {}

    /** Standard sizes from the shop's own printed menu, so adding a sized item is
     *  picking from a short list instead of typing every label from scratch. */
    private static final List<String> PRESET_SIZES = List.of(
        "Pan 7\"", "Small 10\"", "Medium 12\"", "Large 14\"",
        "Small", "Medium", "Large", "Extra Large", "Regular");

    // Fixed widths for both fields — a BorderLayout.CENTER label field would greedily
    // stretch to fill whatever width the row's container happens to have (which grows
    // to fit the widest sibling under GridBagLayout), pushing the price field out past
    // the visible dialog and forcing a horizontal scrollbar to reach it.
    private static final int LABEL_FIELD_WIDTH = 170;
    private static final int PRICE_FIELD_WIDTH = 100;

    private static JPanel buildSizeColumnHeaders() {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

        JLabel sizeHead = UiFactory.muted("SIZE");
        sizeHead.setFont(Theme.FONT_SMALL_BOLD);
        sizeHead.setPreferredSize(new Dimension(LABEL_FIELD_WIDTH, sizeHead.getPreferredSize().height));
        JLabel priceHead = UiFactory.muted("PRICE (Rs)");
        priceHead.setFont(Theme.FONT_SMALL_BOLD);
        priceHead.setPreferredSize(new Dimension(PRICE_FIELD_WIDTH, priceHead.getPreferredSize().height));

        header.add(sizeHead);
        header.add(priceHead);
        header.setMaximumSize(header.getPreferredSize());
        return header;
    }

    private JPanel buildPresetPalette() {
        JPanel palette = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        palette.setOpaque(false);
        for (String size : PRESET_SIZES) {
            JButton chip = UiFactory.secondaryButton(size);
            chip.addActionListener(e -> addSizeRowAndFocusPrice(size, ""));
            palette.add(chip);
        }
        return palette;
    }

    MenuItemEditorDialog(Component parent, MenuDao menuDao, List<Category> categories, MenuItem editing, Runnable onSaved) {
        super(SwingUtilities.getWindowAncestor(parent), editing == null ? "Add Menu Item" : "Edit Menu Item",
            ModalityType.APPLICATION_MODAL);
        this.menuDao = menuDao;
        this.editing = editing;
        this.onSaved = onSaved;

        categoryCombo = new JComboBox<>(categories.toArray(new Category[0]));

        List<Object> groupOptions = new ArrayList<>();
        groupOptions.add(NO_OPTION_GROUP);
        try {
            groupOptions.addAll(menuDao.listOptionGroups(true));
        } catch (DatabaseException e) {
            // Option groups are optional — a load failure here shouldn't block adding/
            // editing an item; the combo just falls back to "None" only.
        }
        optionGroupCombo = new JComboBox<>(groupOptions.toArray());

        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        getContentPane().setBackground(Theme.SURFACE);

        sizeRowsPanel.setOpaque(false);
        sizeRowsPanel.setLayout(new BoxLayout(sizeRowsPanel, BoxLayout.Y_AXIS));

        singlePriceRow.setOpaque(false);
        singlePriceRow.add(UiFactory.label("Rs"), BorderLayout.WEST);
        singlePriceRow.add(singlePriceField, BorderLayout.CENTER);

        customSizeButton.addActionListener(e -> addSizeRowAndFocusLabel("", ""));

        // Scrollable rather than a fixed-height dialog: the preset palette and a longer
        // size list can easily exceed a fixed size, and clipped content with no way to
        // reach it is worse than a scrollbar (see PosPanel's earlier overlap lesson).
        JScrollPane scroll = new JScrollPane(buildForm());
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.SURFACE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);

        if (editing != null) {
            populateForEdit(categories, editing);
            sizedCheck.setEnabled(false); // can't retroactively change sized-ness in this MVP
            categoryCombo.setEnabled(false);
        } else {
            singlePriceField.setText("");
        }

        sizedCheck.addActionListener(e -> updateModeVisibility());
        updateModeVisibility();

        setPreferredSize(new Dimension(520, 620));
        pack();
        setLocationRelativeTo(parent);
    }

    private JComponent buildForm() {
        JPanel outer = new JPanel(new BorderLayout(0, 12));
        outer.setOpaque(false);
        outer.add(UiFactory.title(editing == null ? "Add Menu Item" : "Edit Menu Item"), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 6, 7, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        form.add(UiFactory.labelBold("Category"), c);
        c.gridx = 1;
        categoryCombo.setFont(Theme.FONT_BODY);
        form.add(categoryCombo, c);

        c.gridx = 0; c.gridy = 1;
        form.add(UiFactory.labelBold("Name"), c);
        c.gridx = 1;
        form.add(nameField, c);

        c.gridx = 0; c.gridy = 2;
        form.add(UiFactory.labelBold("Description"), c);
        c.gridx = 1;
        form.add(descField, c);

        c.gridx = 0; c.gridy = 3; c.gridwidth = 1;
        form.add(UiFactory.labelBold("Option group"), c);
        c.gridx = 1;
        optionGroupCombo.setFont(Theme.FONT_BODY);
        form.add(optionGroupCombo, c);

        sizedCheck.setFont(Theme.FONT_BODY);
        sizedCheck.setOpaque(false);
        c.gridx = 0; c.gridy = 4; c.gridwidth = 2;
        form.add(sizedCheck, c);

        c.gridy = 5;
        form.add(singlePriceRow, c);

        JPanel rowsAndHint = new JPanel();
        rowsAndHint.setOpaque(false);
        rowsAndHint.setLayout(new BoxLayout(rowsAndHint, BoxLayout.Y_AXIS));
        sizeColumnHeaders.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowsAndHint.add(sizeColumnHeaders);
        sizeRowsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowsAndHint.add(sizeRowsPanel);
        sizeEmptyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowsAndHint.add(sizeEmptyHint);

        JPanel sizedBlock = new JPanel(new BorderLayout(0, 8));
        sizedBlock.setOpaque(false);
        sizedBlock.add(rowsAndHint, BorderLayout.NORTH);
        JPanel presetWrap = new JPanel(new BorderLayout(0, 4));
        presetWrap.setOpaque(false);
        JLabel presetLabel = UiFactory.muted("Standard sizes — click to add:");
        presetWrap.add(presetLabel, BorderLayout.NORTH);
        presetWrap.add(presetPalette, BorderLayout.CENTER);
        JPanel customWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        customWrap.setOpaque(false);
        customWrap.add(customSizeButton);
        presetWrap.add(customWrap, BorderLayout.SOUTH);
        sizedBlock.add(presetWrap, BorderLayout.SOUTH);
        c.gridy = 6;
        form.add(sizedBlock, c);

        c.gridy = 7;
        form.add(errorLabel, c);

        outer.add(form, BorderLayout.CENTER);
        return outer;
    }

    private JComponent buildButtons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        panel.setOpaque(false);
        JButton cancel = UiFactory.secondaryButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = UiFactory.primaryButton("Save");
        save.addActionListener(e -> save());
        panel.add(cancel);
        panel.add(save);
        getRootPane().setDefaultButton(save);
        return panel;
    }

    private void updateModeVisibility() {
        boolean sized = sizedCheck.isSelected();
        singlePriceRow.setVisible(!sized);
        sizeColumnHeaders.setVisible(sized && !sizeRows.isEmpty());
        sizeRowsPanel.setVisible(sized);
        presetPalette.setVisible(sized);
        customSizeButton.setVisible(sized);
        updateEmptyHintVisibility();
        revalidateForm();
    }

    private void updateEmptyHintVisibility() {
        boolean sized = sizedCheck.isSelected();
        sizeEmptyHint.setVisible(sized && sizeRows.isEmpty());
        sizeColumnHeaders.setVisible(sized && !sizeRows.isEmpty());
    }

    private void revalidateForm() {
        sizeRowsPanel.revalidate();
        sizeRowsPanel.repaint();
        pack();
    }

    /** Adds a preset-chosen size row and jumps focus straight to its price field —
     *  the label is already filled in, so typing the price is the only thing left. */
    private void addSizeRowAndFocusPrice(String label, String price) {
        SizeRow row = addSizeRow(label, price);
        row.price().requestFocusInWindow();
        row.price().selectAll();
    }

    /** Adds a blank custom row and focuses its label field, for a size not in the
     *  standard preset list. */
    private void addSizeRowAndFocusLabel(String label, String price) {
        SizeRow row = addSizeRow(label, price);
        row.label().requestFocusInWindow();
    }

    private SizeRow addSizeRow(String label, String price) {
        JTextField labelField = UiFactory.textField(14);
        labelField.setText(label);
        labelField.setPreferredSize(new Dimension(LABEL_FIELD_WIDTH, labelField.getPreferredSize().height));
        labelField.setMinimumSize(labelField.getPreferredSize());
        labelField.setMaximumSize(labelField.getPreferredSize());

        JTextField priceField = UiFactory.textField(8);
        priceField.setPreferredSize(new Dimension(PRICE_FIELD_WIDTH, priceField.getPreferredSize().height));
        priceField.setMinimumSize(priceField.getPreferredSize());
        priceField.setMaximumSize(priceField.getPreferredSize());
        priceField.setText(price);

        // One flat FlowLayout row — label, price, and Remove together — rather than a
        // BorderLayout(WEST/EAST) split. BorderLayout's own maximumLayoutSize() is
        // unbounded, so under the parent's BoxLayout the row stretched to the full form
        // width, leaving WEST and EAST pinned to opposite edges of that stretched width
        // with a large empty gap between them instead of sitting next to each other.
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.SPACE_XS, 0));
        row.add(labelField);
        row.add(priceField);

        JButton remove = UiFactory.secondaryButton("Remove");
        remove.addActionListener(e -> {
            sizeRows.removeIf(r -> r.row() == row);
            sizeRowsPanel.remove(row);
            updateEmptyHintVisibility();
            revalidateForm();
        });
        row.add(remove);
        // FlowLayout also reports an unbounded maximumLayoutSize by default, so this
        // still needs an explicit cap to stop BoxLayout from stretching it too.
        row.setMaximumSize(row.getPreferredSize());

        SizeRow sizeRow = new SizeRow(row, labelField, priceField);
        sizeRows.add(sizeRow);
        sizeRowsPanel.add(row);
        updateEmptyHintVisibility();
        revalidateForm();
        return sizeRow;
    }

    private void populateForEdit(List<Category> categories, MenuItem item) {
        for (Category c : categories) {
            if (c.id() == item.categoryId()) {
                categoryCombo.setSelectedItem(c);
                break;
            }
        }
        nameField.setText(item.name());
        descField.setText(item.description() == null ? "" : item.description());
        sizedCheck.setSelected(item.sized());

        if (item.hasOptions()) {
            for (int i = 0; i < optionGroupCombo.getItemCount(); i++) {
                Object o = optionGroupCombo.getItemAt(i);
                if (o instanceof rps.model.OptionGroup g && g.id() == item.optionGroup().id()) {
                    optionGroupCombo.setSelectedItem(o);
                    break;
                }
            }
        } else {
            optionGroupCombo.setSelectedItem(NO_OPTION_GROUP);
        }

        sizeRows.clear();
        sizeRowsPanel.removeAll();
        if (item.sized()) {
            for (MenuItemVariant v : item.variants()) {
                addSizeRow(v.sizeLabel() == null ? "" : v.sizeLabel(), v.price().asBigDecimal().toPlainString());
            }
            if (item.variants().isEmpty()) addSizeRow("", "");
        } else {
            singlePriceField.setText(item.variants().isEmpty() ? ""
                : item.variants().get(0).price().asBigDecimal().toPlainString());
        }
    }

    private void save() {
        String name = nameField.getText().trim();
        if (!Validators.isValidName(name)) {
            showError("Enter a valid item name.");
            return;
        }
        boolean sized = sizedCheck.isSelected();

        List<String> labels = new ArrayList<>();
        List<Money> prices = new ArrayList<>();

        if (sized) {
            for (SizeRow r : sizeRows) {
                String label = r.label().getText().trim();
                Money price = Validators.parsePrice(r.price().getText().trim());
                if (price == null) {
                    showError("Enter a valid price (up to 2 decimals) for every size row.");
                    return;
                }
                if (label.isEmpty()) {
                    showError("Enter a size label for every row, or uncheck \"comes in sizes\".");
                    return;
                }
                labels.add(label);
                prices.add(price);
            }
        } else {
            Money price = Validators.parsePrice(singlePriceField.getText().trim());
            if (price == null) {
                showError("Enter a valid price (up to 2 decimals).");
                return;
            }
            labels.add(null);
            prices.add(price);
        }
        if (prices.isEmpty()) {
            showError("Add at least one price.");
            return;
        }

        Object selectedGroup = optionGroupCombo.getSelectedItem();
        Integer optionGroupId = selectedGroup instanceof rps.model.OptionGroup g ? g.id() : null;

        try {
            int itemId;
            if (editing == null) {
                Category category = (Category) categoryCombo.getSelectedItem();
                if (category == null) {
                    showError("Choose a category.");
                    return;
                }
                itemId = menuDao.createMenuItem(category.id(), name, descField.getText().trim(), sized, labels, prices).id();
            } else {
                menuDao.updateMenuItem(editing.id(), name, descField.getText().trim());
                for (int i = 0; i < editing.variants().size() && i < prices.size(); i++) {
                    menuDao.updateVariantPrice(editing.variants().get(i).id(), prices.get(i));
                }
                itemId = editing.id();
            }
            menuDao.setItemOptionGroup(itemId, optionGroupId);
            onSaved.run();
            dispose();
        } catch (DatabaseException e) {
            showError(e.getMessage());
        }
    }

    private void showError(String message) {
        errorLabel.setText(message);
    }
}
