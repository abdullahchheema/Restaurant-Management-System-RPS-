package rps.ui;

import rps.app.Session;
import rps.db.Db;
import rps.db.DatabaseException;
import rps.db.MenuDao;
import rps.model.Category;
import rps.model.MenuItem;
import rps.model.Role;
import rps.ui.icon.LineIcon;
import rps.ui.theme.Card;
import rps.ui.theme.EmptyState;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.Validators;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Category -> Item -> Size/Price admin screen. Selection is always by domain object,
 * never by parsing rendered text — this is what lets numeric IDs stay off the screen
 * entirely (plan Phase 4).
 */
public final class MenuAdminPanel extends JPanel {

    private final Db db;
    private final Session session;
    private final MenuDao menuDao;

    private List<Category> categories = List.of();
    private Category selectedCategory;
    private final JPanel categoryList = new JPanel();
    private final CardLayout categoryCards = new CardLayout();
    private final JPanel categoryCardHolder = new JPanel(categoryCards);
    private final EmptyState categoryEmpty = new EmptyState(LineIcon.CATEGORY_PIZZA, "No categories yet",
        "Add one to start building the menu.");

    private List<MenuItem> allItems = List.of();
    private List<MenuItem> shownItems = List.of();
    private MenuItem selectedItem;
    private final WrapPanel itemGrid = new WrapPanel(new WrapLayout(FlowLayout.LEFT, 14, 14));
    private final CardLayout itemCards = new CardLayout();
    private final JPanel itemCardHolder = new JPanel(itemCards);
    private final EmptyState itemEmpty = new EmptyState(LineIcon.CATEGORY_PIZZA, "No items in this category",
        "Add a menu item to get started.");

    private final JButton editButton = actionButton("Edit", LineIcon.EDIT, Theme.TEXT_MUTED);
    private final JButton toggleButton = actionButton("Toggle Available", LineIcon.EYE_SHOW, Theme.TEXT_MUTED);
    private final JButton deleteButton = actionButton("Delete", LineIcon.DELETE, Theme.STATUS_CANCELLED);

    public MenuAdminPanel(Db db, Session session) {
        this.db = db;
        this.session = session;
        this.menuDao = new MenuDao(db);

        session.require(Role.MANAGER);

        setLayout(new BorderLayout(10, 14));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG));

        add(UiFactory.title("Menu Management"), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(16, 10));
        body.setOpaque(false);
        body.add(buildCategoryColumn(), BorderLayout.WEST);
        body.add(buildItemColumn(), BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        reload();
    }

    // -------------------------------------------------------------- left: category pills

    private JComponent buildCategoryColumn() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(230, 0));
        panel.add(UiFactory.heading("Categories"), BorderLayout.NORTH);

        categoryList.setOpaque(false);
        categoryList.setLayout(new BoxLayout(categoryList, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(categoryList);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        categoryCardHolder.setOpaque(false);
        categoryCardHolder.add(scroll, "list");
        categoryCardHolder.add(categoryEmpty, "empty");
        panel.add(categoryCardHolder, BorderLayout.CENTER);

        JButton addCategory = pillOutlineButton("New Category", LineIcon.PLUS);
        addCategory.addActionListener(e -> promptNewCategory());
        JPanel addWrap = new JPanel(new BorderLayout());
        addWrap.setOpaque(false);
        addWrap.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        addWrap.add(addCategory, BorderLayout.CENTER);
        panel.add(addWrap, BorderLayout.SOUTH);

        return panel;
    }

    private void rebuildCategoryList() {
        categoryList.removeAll();
        for (Category c : categories) {
            categoryList.add(categoryPill(c));
            categoryList.add(Box.createVerticalStrut(8));
        }
        categoryList.revalidate();
        categoryList.repaint();
    }

    private JComponent categoryPill(Category c) {
        boolean selected = c.equals(selectedCategory);
        RoundedToggle pill = new RoundedToggle(c.name(), selected, Theme.RADIUS_MD);
        pill.setAlignmentX(Component.LEFT_ALIGNMENT);
        pill.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        pill.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                selectedCategory = c;
                rebuildCategoryList();
                filterItems();
            }
        });
        return pill;
    }

    // -------------------------------------------------------------- center: item grid

    private JComponent buildItemColumn() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setOpaque(false);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(UiFactory.heading("Menu Items"), BorderLayout.WEST);
        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerButtons.setOpaque(false);
        JButton addOptionGroup = pillOutlineButton("New Option Group", LineIcon.PLUS);
        addOptionGroup.addActionListener(e -> promptNewOptionGroup());
        headerButtons.add(addOptionGroup);
        JButton addItem = pillPrimaryButton("Add Menu Item", LineIcon.PLUS);
        addItem.addActionListener(e -> new MenuItemEditorDialog(this, menuDao, categories, null, this::reload)
            .setVisible(true));
        headerButtons.add(addItem);
        header.add(headerButtons, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        itemGrid.setOpaque(false);
        JScrollPane scroll = new JScrollPane(itemGrid,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        itemCardHolder.setOpaque(false);
        itemCardHolder.add(scroll, "list");
        itemCardHolder.add(itemEmpty, "empty");
        panel.add(itemCardHolder, BorderLayout.CENTER);

        editButton.addActionListener(e -> editSelected());
        toggleButton.addActionListener(e -> toggleAvailableSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        updateActionButtons();

        JPanel actionsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 12));
        actionsBar.setOpaque(false);
        RoundedContainer pill = new RoundedContainer(new FlowLayout(FlowLayout.LEFT, 4, 4));
        pill.setOpaque(false);
        pill.add(editButton);
        pill.add(toggleButton);
        pill.add(deleteButton);
        actionsBar.add(pill);
        panel.add(actionsBar, BorderLayout.SOUTH);

        return panel;
    }

    private void rebuildItemGrid() {
        itemGrid.removeAll();
        for (MenuItem item : shownItems) {
            itemGrid.add(itemCard(item));
        }
        itemGrid.revalidate();
        itemGrid.repaint();
    }

    private JComponent itemCard(MenuItem item) {
        Card card = new Card();
        card.setPreferredSize(new Dimension(228, 92));
        card.setLayout(new BorderLayout(14, 0));
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        LineIcon icon = LineIcon.forCategory(selectedCategory == null ? "" : selectedCategory.name());
        JComponent badge = iconBadge(icon);
        card.add(badge, BorderLayout.WEST);

        JPanel textCol = new JPanel();
        textCol.setOpaque(false);
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        JLabel name = UiFactory.labelBold(item.name());
        if (!item.available()) name.setForeground(Theme.TEXT_MUTED);
        textCol.add(name);
        textCol.add(Box.createVerticalStrut(4));
        String priceText = item.sized() ? "from " + item.lowestPrice().format() : item.singlePrice().format();
        if (!item.available()) priceText += "  ·  Unavailable";
        textCol.add(UiFactory.muted(priceText));
        card.add(textCol, BorderLayout.CENTER);

        MouseAdapter click = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                selectedItem = item;
                updateActionButtons();
                if (e.getClickCount() == 2) editSelected();
            }
        };
        card.addMouseListener(click);
        badge.addMouseListener(click);
        textCol.addMouseListener(click);
        name.addMouseListener(click);

        return card;
    }

    private JComponent iconBadge(LineIcon icon) {
        JPanel badge = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.SURFACE_HOVER);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), Theme.RADIUS_MD * 2, Theme.RADIUS_MD * 2);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setOpaque(false);
        badge.setPreferredSize(new Dimension(52, 52));
        badge.add(new JLabel((icon != null ? icon : LineIcon.CATEGORY_PIZZA).of(26, Theme.TEXT_MUTED)));
        return badge;
    }

    private void updateActionButtons() {
        boolean hasSelection = selectedItem != null;
        editButton.setEnabled(hasSelection);
        toggleButton.setEnabled(hasSelection);
        deleteButton.setEnabled(hasSelection);
    }

    // -------------------------------------------------------------- shared small components

    /** Small pill button used inside the actions bar — icon + text, no border, matching
     *  the reference's flat toolbar buttons rather than the app's usual outlined
     *  secondaryButton box. */
    private static JButton actionButton(String text, LineIcon icon, Color iconColor) {
        JButton b = new JButton(text);
        b.setFont(Theme.FONT_BODY);
        b.setForeground(Theme.TEXT);
        b.setIcon(icon.of(15, iconColor));
        b.setIconTextGap(8);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static JButton pillPrimaryButton(String text, LineIcon icon) {
        JButton b = pillButton(text, Theme.PRIMARY, Theme.PRIMARY_DARK, Theme.ON_PRIMARY);
        b.setIcon(icon.of(15, Theme.ON_PRIMARY));
        b.setIconTextGap(8);
        return b;
    }

    private static JButton pillOutlineButton(String text, LineIcon icon) {
        JButton b = pillButton(text, Theme.SURFACE, Theme.SURFACE_HOVER, Theme.TEXT);
        b.setIcon(icon.of(14, Theme.TEXT_MUTED));
        b.setIconTextGap(8);
        return b;
    }

    /** Fully rounded (pill) button — the global Button.arc UIManager setting only covers
     *  the app's standard small radius, not a true pill. */
    private static JButton pillButton(String text, Color bg, Color hoverBg, Color fg) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = getHeight();
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                if (bg == Theme.SURFACE) {
                    g2.setColor(Theme.BORDER);
                    g2.setStroke(new BasicStroke(1.3f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(Theme.FONT_BODY_BOLD);
        b.setForeground(fg);
        b.setBackground(bg);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setOpaque(false);
        b.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(hoverBg); b.repaint(); }
            @Override public void mouseExited(MouseEvent e) { b.setBackground(bg); b.repaint(); }
        });
        return b;
    }

    /** A full-width rounded toggle for the category list — filled burgundy when
     *  selected, outlined ivory otherwise, matching the reference's pill list rather
     *  than a plain JList selection highlight. */
    private static final class RoundedToggle extends JPanel {
        RoundedToggle(String text, boolean selected, int radius) {
            setOpaque(false);
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            JLabel label = new JLabel(text);
            label.setFont(selected ? Theme.FONT_BODY_BOLD : Theme.FONT_BODY);
            label.setForeground(selected ? Theme.ON_PRIMARY : Theme.TEXT);
            add(label, BorderLayout.WEST);
            putClientProperty("selected", selected);
            putClientProperty("radius", radius);
        }

        @Override
        protected void paintComponent(Graphics g) {
            boolean selected = Boolean.TRUE.equals(getClientProperty("selected"));
            int radius = (int) getClientProperty("radius") * 2;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(selected ? Theme.PRIMARY : Theme.SURFACE);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            if (!selected) {
                g2.setColor(Theme.BORDER);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Rounded pill container that groups the Edit/Toggle/Delete buttons into one
     *  floating toolbar, matching the reference rather than three loose buttons. */
    private static final class RoundedContainer extends JPanel {
        RoundedContainer(LayoutManager layout) {
            super(layout);
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.SURFACE);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.setColor(Theme.BORDER);
            g2.setStroke(new BasicStroke(1.3f));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Reads the color from Theme at render time rather than a hardcoded literal, so an
     *  HTML-rendered label can't silently keep a stale hex value after a palette change.
     *  Also used by StaffAdminPanel's list renderer. */
    static String toHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void reload() {
        Busy.call(this, () -> new Object[]{menuDao.listCategories(true), menuDao.listAllLiveItems()})
            .message("Loading menu…")
            .onSuccess(this::applyReload)
            .onError(e -> JOptionPane.showMessageDialog(this, "Could not load the menu: " + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE))
            .start();
    }

    @SuppressWarnings("unchecked")
    private void applyReload(Object[] result) {
        categories = (List<Category>) result[0];
        if (selectedCategory != null) {
            selectedCategory = categories.stream()
                .filter(c -> c.id() == selectedCategory.id()).findFirst().orElse(null);
        }
        if (selectedCategory == null && !categories.isEmpty()) {
            selectedCategory = categories.get(0);
        }
        rebuildCategoryList();
        categoryCards.show(categoryCardHolder, categories.isEmpty() ? "empty" : "list");

        allItems = (List<MenuItem>) result[1];
        filterItems();
    }

    private void filterItems() {
        selectedItem = null;
        updateActionButtons();
        if (selectedCategory == null) {
            shownItems = List.of();
            itemCards.show(itemCardHolder, "empty");
            return;
        }
        List<MenuItem> filtered = new ArrayList<>();
        for (MenuItem item : allItems) {
            if (item.categoryId() == selectedCategory.id()) filtered.add(item);
        }
        shownItems = filtered;
        rebuildItemGrid();
        itemCards.show(itemCardHolder, shownItems.isEmpty() ? "empty" : "list");
    }

    private void promptNewCategory() {
        String name = JOptionPane.showInputDialog(this, "Category name:", "New Category", JOptionPane.PLAIN_MESSAGE);
        if (Validators.isBlank(name)) return;
        try {
            menuDao.createCategory(name.trim(), (categories.size() + 1) * 10);
            reload();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Could not create category", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Minimal creation flow for an option group: name + comma-separated values. Editing
     *  or deleting an existing group's values isn't exposed here — creating a fresh one
     *  covers the common case (a wrong value is easiest to fix by adding a corrected one
     *  and leaving the old one unattached from any item). */
    private void promptNewOptionGroup() {
        String name = JOptionPane.showInputDialog(this,
            "Option group name (e.g. \"Flavor\", \"Crust Type\"):", "New Option Group", JOptionPane.PLAIN_MESSAGE);
        if (Validators.isBlank(name)) return;
        String valuesText = JOptionPane.showInputDialog(this,
            "Values, comma-separated (e.g. \"Thin, Pan, Stuffed\"):", "New Option Group", JOptionPane.PLAIN_MESSAGE);
        if (Validators.isBlank(valuesText)) return;
        List<String> values = new ArrayList<>();
        for (String v : valuesText.split(",")) {
            if (!v.isBlank()) values.add(v.trim());
        }
        if (values.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter at least one value.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            menuDao.createOptionGroup(name.trim(), values);
            JOptionPane.showMessageDialog(this,
                "Option group created. Attach it to an item from Edit → Option group.");
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Could not create option group", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void editSelected() {
        if (selectedItem == null) return;
        new MenuItemEditorDialog(this, menuDao, categories, selectedItem, this::reload).setVisible(true);
    }

    private void toggleAvailableSelected() {
        if (selectedItem == null) return;
        try {
            menuDao.setItemAvailable(selectedItem.id(), !selectedItem.available());
            reload();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelected() {
        if (selectedItem == null) return;
        try {
            boolean hasHistory = menuDao.hasOrderHistory(selectedItem.id());
            String message = hasHistory
                ? selectedItem.name() + " appears on past orders. It will be removed from the menu but kept for records. Continue?"
                : "Delete " + selectedItem.name() + " permanently?";
            int confirm = JOptionPane.showConfirmDialog(this, message, "Confirm delete", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;

            String deletedName = selectedItem.name();
            MenuDao.DeleteOutcome outcome = menuDao.deleteMenuItem(selectedItem.id());
            reload();
            if (outcome == MenuDao.DeleteOutcome.SOFT_DELETED) {
                JOptionPane.showMessageDialog(this, deletedName + " was removed from the menu (history kept).");
            }
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
