package rps.ui;

import rps.app.Session;
import rps.backup.OffsiteBackupService;
import rps.db.Db;
import rps.db.DatabaseException;
import rps.db.MenuDao;
import rps.db.OrderDao;
import rps.model.Category;
import rps.model.DiscountMode;
import rps.model.DraftLine;
import rps.model.MenuItem;
import rps.model.MenuItemVariant;
import rps.model.Order;
import rps.model.OrderDraft;
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
import java.util.concurrent.atomic.AtomicBoolean;

public final class PosPanel extends JPanel {

    private static final int MENU_POLL_MS = 5000;

    private final Db db;
    private final Session session;
    private final MenuDao menuDao;
    private final OrderDao orderDao;
    private final ReceiptPrinter receiptPrinter = new ReceiptPrinter();
    private final OffsiteBackupService backupService;

    private final OrderDraft draft = new OrderDraft();

    // Plain FlowLayout reports a single row's preferred height no matter how many
    // children it has, so with more than a row's worth of categories the overflow was
    // clipped rather than wrapped — WrapLayout (already used by the item tile grid)
    // recomputes its preferred height for the width it's actually given.
    private final JPanel categoryStrip = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));
    private final JTextField menuSearchField = UiFactory.textField(18);
    private final WrapPanel tileGrid = new WrapPanel(new WrapLayout(FlowLayout.LEFT, 12, 12));
    private final JPanel ticketList = new JPanel();
    private final JLabel totalLabel = titleLabelInPrimary();
    private final JLabel fieldsError = UiFactory.errorText(" ");

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

    private final JTextField cashTenderedField = UiFactory.textField(10);
    private final JLabel changeDueLabel = UiFactory.labelBold(" ");

    private final JButton confirmButton = confirmButtonStyled();
    /** True from the moment Confirm is clicked until the save resolves. Confirm is
     *  re-enabled by updateConfirmEnabled(), which is reached from refreshTicket() /
     *  refreshTotals() / validateFields() — so merely disabling the button in the click
     *  handler was not enough: adding a ticket line during the save re-enabled it and a
     *  second click submitted the same order again (reproduced against the real panel).
     *  This flag is the authoritative gate; the button state is derived from it. */
    private boolean submitting = false;
    private final JLabel emptyTicketLabel = emptyStateLabel();

    private List<Category> categories = List.of();
    private List<MenuItem> allItems = List.of();
    private Category activeCategory;
    private String searchQuery = "";

    // Menu edits made from the Menu tab (or another terminal) previously never reached
    // an already-open POS tab, since PosPanel loads the catalog once in its constructor
    // and JTabbedPane just shows/hides the same instance rather than recreating it —
    // switching back to "New Order" didn't help. Poll the same way DashboardPanel polls
    // orders: a cheap fingerprint check, and only re-fetch/rebuild the tile grid when the
    // catalog actually changed.
    private final Timer menuPollTimer = new Timer(MENU_POLL_MS, e -> pollMenu());
    private final AtomicBoolean menuPollInFlight = new AtomicBoolean(false);
    private String lastMenuFingerprint = "";

    public PosPanel(Db db, Session session, OffsiteBackupService backupService) {
        this.db = db;
        this.session = session;
        this.menuDao = new MenuDao(db);
        this.orderDao = new OrderDao(db);
        this.backupService = backupService;

        setLayout(new BorderLayout());
        setBackground(Theme.BACKGROUND);

        add(buildLeft(), BorderLayout.CENTER);
        add(buildRight(), BorderLayout.EAST);

        reload();
        refreshTicket();

        menuPollTimer.setRepeats(true);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        menuPollTimer.start();
    }

    @Override
    public void removeNotify() {
        menuPollTimer.stop();
        super.removeNotify();
    }

    /** Cheap check on a background thread; only re-fetches and rebuilds the tile grid
     *  when the catalog actually changed. Never touches the in-progress order draft —
     *  a cashier mid-order must never lose their ticket because a price changed
     *  somewhere else. */
    private void pollMenu() {
        if (!menuPollInFlight.compareAndSet(false, true)) return;

        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                String fp = menuDao.fingerprint();
                if (fp.equals(lastMenuFingerprint)) return null;
                lastMenuFingerprint = fp;
                return new Object[]{menuDao.listCategories(true), menuDao.listAvailableItems()};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done() {
                menuPollInFlight.set(false);
                try {
                    Object[] result = get();
                    if (result == null) return;
                    applyReloadedMenu((List<Category>) result[0], (List<MenuItem>) result[1]);
                } catch (Exception ex) {
                    // Transient poll failure: stay on the last-good menu rather than
                    // popping an error dialog on every 5s tick.
                    System.err.println("Menu refresh failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------- left: categories + tiles

    private JComponent buildLeft() {
        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setOpaque(false);
        left.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        // BoxLayout rather than BorderLayout(NORTH/SOUTH) — BorderLayout reserves a
        // vgap on both sides of an (absent) CENTER even with nothing in it, which read
        // as an oversized, unexplained gap between the search row and the category
        // strip. A single explicit strut here is the only gap between them.
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        menuSearchField.putClientProperty("JTextField.placeholderText", "Search menu…");
        menuSearchField.getDocument().addDocumentListener((SimpleDocListener) () -> {
            searchQuery = menuSearchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
            rebuildTileGrid();
        });
        // A heading on the left balances the search field on the right — previously
        // this row was empty apart from the field, which read as a large dead strip of
        // whitespace rather than an intentional header.
        JPanel searchRow = new JPanel(new BorderLayout(12, 0));
        searchRow.setOpaque(false);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.add(UiFactory.heading("Menu"), BorderLayout.WEST);
        searchRow.add(menuSearchField, BorderLayout.EAST);
        top.add(searchRow);
        top.add(Box.createVerticalStrut(12));

        categoryStrip.setOpaque(false);
        categoryStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(categoryStrip);
        left.add(top, BorderLayout.NORTH);

        tileGrid.setOpaque(false);
        JScrollPane scroll = new JScrollPane(tileGrid,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        left.add(scroll, BorderLayout.CENTER);

        return left;
    }

    private void reload() {
        try {
            lastMenuFingerprint = menuDao.fingerprint();
            applyReloadedMenu(menuDao.listCategories(true), menuDao.listAvailableItems());
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, "Could not load the menu: " + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Shared by the initial synchronous load and the background poll. Keeps the current
     *  category selected across a refresh (by id, since a re-fetch produces new Category
     *  instances) rather than silently jumping back to the first category. */
    private void applyReloadedMenu(List<Category> newCategories, List<MenuItem> newItems) {
        categories = newCategories;
        allItems = newItems;

        Category stillSelected = null;
        if (activeCategory != null) {
            for (Category c : categories) {
                if (c.id() == activeCategory.id()) {
                    stillSelected = c;
                    break;
                }
            }
        }
        activeCategory = stillSelected != null ? stillSelected
            : (categories.isEmpty() ? null : categories.get(0));

        rebuildCategoryStrip();
        rebuildTileGrid();
    }

    private void rebuildCategoryStrip() {
        categoryStrip.removeAll();
        ButtonGroup group = new ButtonGroup();
        for (Category c : categories) {
            JToggleButton btn = UiFactory.chipToggle(c.name(), c.equals(activeCategory), rps.ui.icon.LineIcon.forCategory(c.name()));
            btn.addActionListener(e -> {
                activeCategory = c;
                rebuildCategoryStrip();
                rebuildTileGrid();
            });
            group.add(btn);
            categoryStrip.add(btn);
        }
        categoryStrip.revalidate();
        categoryStrip.repaint();
    }

    private void rebuildTileGrid() {
        tileGrid.removeAll();
        boolean searching = !searchQuery.isEmpty();
        for (MenuItem item : allItems) {
            if (item.variants().isEmpty()) continue;
            if (searching) {
                if (!item.name().toLowerCase(java.util.Locale.ROOT).contains(searchQuery)) continue;
            } else if (activeCategory == null || item.categoryId() != activeCategory.id()) {
                continue;
            }
            tileGrid.add(buildTile(item));
        }
        tileGrid.revalidate();
        tileGrid.repaint();
    }

    private JComponent buildTile(MenuItem item) {
        JPanel tile = new JPanel(new BorderLayout(4, 4));
        tile.setPreferredSize(new Dimension(196, 150));
        tile.setBackground(Theme.SURFACE);
        tile.setBorder(UiFactory.tileBorder(Theme.BORDER));
        tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Deal names carry their full contents (e.g. "Heavy Deal: 2 Chicken Burger + ...")
        // so the kitchen ticket and receipt — which print whatever the item's name is —
        // show exactly what's included with no separate schema for bundle contents. That
        // makes some names too long for this fixed-height tile, so the tile itself shows
        // a shortened form with the full text as a tooltip; the order always uses the
        // real item, never the truncated label.
        JLabel name = new JLabel("<html>" + escape(tileDisplayName(item.name())) + "</html>");
        name.setFont(Theme.FONT_BODY_BOLD);
        name.setForeground(Theme.TEXT);
        name.setVerticalAlignment(SwingConstants.TOP);
        if (item.name().length() > 60) {
            name.setToolTipText(escape(item.name()));
            tile.setToolTipText(escape(item.name()));
        }
        tile.add(name, BorderLayout.NORTH);

        TileBadge badge = new TileBadge(categoryNameFor(item), 64);
        JPanel badgeWrap = new JPanel(new BorderLayout());
        badgeWrap.setOpaque(false);
        badgeWrap.add(badge, BorderLayout.EAST);
        tile.add(badgeWrap, BorderLayout.CENTER);

        String priceText = item.sized() ? "from " + item.lowestPrice().format() : item.singlePrice().format();
        JLabel price = new JLabel(priceText);
        price.setFont(Theme.FONT_BODY_BOLD);
        price.setForeground(Theme.TEXT);
        tile.add(price, BorderLayout.SOUTH);

        java.awt.event.MouseAdapter click = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                onItemPicked(item);
            }
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                tile.setBackground(Theme.PRIMARY_TINT);
                tile.setBorder(UiFactory.tileBorder(Theme.PRIMARY));
            }
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                tile.setBackground(Theme.SURFACE);
                tile.setBorder(UiFactory.tileBorder(Theme.BORDER));
            }
        };
        tile.addMouseListener(click);
        name.addMouseListener(click);
        price.addMouseListener(click);
        badge.addMouseListener(click);

        return tile;
    }

    private static JLabel titleLabelInPrimary() {
        JLabel l = UiFactory.title("Rs 0.00");
        l.setForeground(Theme.PRIMARY);
        return l;
    }

    private void onItemPicked(MenuItem item) {
        MenuItemVariant chosen;
        rps.model.OptionValue chosenOption = null;
        if ((item.sized() && item.variants().size() > 1) || item.hasOptions()) {
            SizePickerDialog.Selection selection =
                SizePickerDialog.pick(this, item, rps.ui.icon.LineIcon.forCategory(categoryNameFor(item)));
            if (selection == null) return; // dialog closed without confirming
            chosen = selection.variant();
            chosenOption = selection.option();
        } else {
            chosen = item.variants().get(0);
        }

        // Two lines for the same variant with different chosen options are distinct
        // tickets rows (e.g. two of the same pizza with different crusts), so the dedupe
        // key is the pair, not the variant alone.
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

    // -------------------------------------------------------------- right: ticket + order details

    private JComponent buildRight() {
        JPanel right = new JPanel(new BorderLayout(0, 0));
        right.setPreferredSize(new Dimension(460, 0));
        right.setBackground(Theme.SURFACE);
        right.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20));
        header.add(UiFactory.title("Current Order"), BorderLayout.WEST);
        right.add(header, BorderLayout.NORTH);

        ticketList.setOpaque(false);
        ticketList.setLayout(new BoxLayout(ticketList, BoxLayout.Y_AXIS));
        ticketList.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Ticket lines and the order-details controls (type/phone/discount/cash/total)
        // are ONE scrollable column, not a fixed CENTER carved against a fixed SOUTH.
        // Splitting them fought over space: once the discount/address rows made the
        // details section taller than the window, BorderLayout gave CENTER a negative
        // height and the two regions visually overlapped. A single scrollable column
        // has no such failure mode — if content ever exceeds the visible height, it
        // scrolls, and Confirm is still reachable rather than clipped off-screen.
        ScrollableColumn column = new ScrollableColumn(null);
        column.setOpaque(true);
        column.setBackground(Theme.SURFACE);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.add(ticketList);
        column.add(buildOrderDetailsSection());

        JScrollPane scroll = new JScrollPane(column,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        right.add(scroll, BorderLayout.CENTER);
        return right;
    }

    private JComponent buildOrderDetailsSection() {
        JPanel panel = new JPanel();
        panel.setOpaque(true);
        panel.setBackground(Theme.SURFACE);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
            BorderFactory.createEmptyBorder(16, 20, 20, 20)));
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
        discountValueRow.setVisible(false);
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
        panel.add(labeledField("Cash Paid (leave blank if unpaid)", cashTenderedField));
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
        panel.add(Box.createVerticalStrut(14));

        confirmButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        confirmButton.setEnabled(false);
        confirmButton.addActionListener(e -> confirmOrder());
        panel.add(confirmButton);

        return panel;
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

    private JLabel sectionLabel(String text) {
        JLabel l = UiFactory.muted(text);
        l.setFont(Theme.FONT_SMALL_BOLD);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JComponent buildOrderTypeChips() {
        JPanel row = new JPanel(new GridLayout(1, OrderType.values().length, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        ButtonGroup group = new ButtonGroup();
        for (OrderType type : OrderType.values()) {
            JToggleButton btn = UiFactory.chipToggle(type.label(), type == draft.type(), orderTypeIcon(type));
            btn.addActionListener(e -> {
                draft.setType(type);
                updateConditionalFields();
            });
            group.add(btn);
            row.add(btn);
        }
        return row;
    }

    private String categoryNameFor(MenuItem item) {
        for (Category c : categories) {
            if (c.id() == item.categoryId()) return c.name();
        }
        return "";
    }

    /** Small circular badge decoration on each menu tile — a pizza-slice doodle for
     *  pizza categories (matching the reference design), or the category's own icon
     *  centered in the same ring for everything else, so every tile reads as one
     *  consistent "circular badge" family rather than pizza tiles looking special. */
    private static final class TileBadge extends JComponent {
        private final String categoryName;
        private final int size;

        TileBadge(String categoryName, int size) {
            this.categoryName = categoryName;
            this.size = size;
            setPreferredSize(new Dimension(size, size));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.3f));
            g2.setColor(Theme.BORDER);
            g2.draw(new java.awt.geom.Ellipse2D.Float(1, 1, size - 2, size - 2));

            if (categoryNameFor().toLowerCase(java.util.Locale.ROOT).contains("pizza")) {
                drawPizzaDoodle(g2);
            } else {
                rps.ui.icon.LineIcon icon = rps.ui.icon.LineIcon.forCategory(categoryName);
                if (icon != null) {
                    int iconSize = Math.round(size * 0.52f);
                    icon.of(iconSize, Theme.TEXT_MUTED)
                        .paintIcon(this, g2, (size - iconSize) / 2, (size - iconSize) / 2);
                }
            }
            g2.dispose();
        }

        private String categoryNameFor() {
            return categoryName == null ? "" : categoryName;
        }

        private void drawPizzaDoodle(Graphics2D g2) {
            float cx = size / 2f, cy = size / 2f;
            float outer = size * 0.36f, inner = size * 0.14f;
            g2.setColor(Theme.TEXT_MUTED);
            g2.setStroke(new BasicStroke(1.1f));
            g2.draw(new java.awt.geom.Ellipse2D.Float(cx - outer, cy - outer, outer * 2, outer * 2));
            g2.draw(new java.awt.geom.Ellipse2D.Float(cx - inner, cy - inner, inner * 2, inner * 2));
            for (int i = 0; i < 6; i++) {
                double angle = Math.toRadians(i * 60);
                float x1 = cx + (float) (Math.cos(angle) * inner);
                float y1 = cy + (float) (Math.sin(angle) * inner);
                float x2 = cx + (float) (Math.cos(angle) * outer);
                float y2 = cy + (float) (Math.sin(angle) * outer);
                g2.draw(new java.awt.geom.Line2D.Float(x1, y1, x2, y2));
            }
            for (int i = 0; i < 5; i++) {
                double angle = Math.toRadians(i * 72 + 30);
                float r = (outer + inner) / 2f;
                float dx = cx + (float) (Math.cos(angle) * r);
                float dy = cy + (float) (Math.sin(angle) * r);
                g2.fill(new java.awt.geom.Ellipse2D.Float(dx - 1.5f, dy - 1.5f, 3, 3));
            }
        }
    }

    private static rps.ui.icon.LineIcon orderTypeIcon(OrderType type) {
        return switch (type) {
            case DINE_IN -> rps.ui.icon.LineIcon.DINE_IN;
            case TAKEAWAY -> rps.ui.icon.LineIcon.TAKEAWAY;
            case DELIVERY -> rps.ui.icon.LineIcon.DELIVERY;
        };
    }

    private JComponent buildDiscountChips() {
        JPanel row = new JPanel(new GridLayout(1, 3, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        ButtonGroup group = new ButtonGroup();

        discountNoneBtn.setSelected(true);
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
                // Clamp visually against the current subtotal so the cashier sees the
                // real effect immediately; the server clamps again as the source of truth.
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

    /** A label-above-field row that always fills the available width — avoids the classic
     *  GridBagLayout footgun (missing fill/weightx) that once left fields collapsed to
     *  their minimum size. */
    private static JComponent labeledField(String labelText, JTextField field) {
        return labeledFieldWithLabel(UiFactory.muted(labelText), field);
    }

    /** Same layout as labeledField, but takes an existing JLabel so its text can be
     *  changed later — used for the phone label, which reads "Phone number *" or
     *  "Phone number (optional)" depending on the selected order type. */
    private static JComponent labeledFieldWithLabel(JLabel label, JTextField field) {
        JPanel wrap = new JPanel(new BorderLayout(0, 6));
        wrap.setOpaque(false);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        wrap.add(label, BorderLayout.NORTH);
        wrap.add(field, BorderLayout.CENTER);
        return wrap;
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

        updateConfirmEnabled();
    }

    private void refreshTotals() {
        totalLabel.setText(draft.estimatedTotal().format());
        Money discount = draft.estimatedDiscount();
        discountAmountLabel.setText(discount.isZero() ? " " : "Discount: -" + discount.format());

        Money deliveryFee = draft.estimatedDeliveryFee();
        deliveryFeeLabel.setVisible(!deliveryFee.isZero());
        if (!deliveryFee.isZero()) {
            deliveryFeeLabel.setText("Delivery fee: +" + deliveryFee.format()
                + " (orders under " + Money.fromDouble(rps.util.AppSettings.get().deliveryFeeThreshold()).format() + ")");
        }

        if (draft.cashTendered() == null) {
            changeDueLabel.setText(" ");
        } else {
            boolean enough = draft.isCashSufficient();
            Money amount = enough
                ? draft.estimatedChangeDue()
                : draft.estimatedTotal().subtract(draft.cashTendered());
            // A short amount no longer blocks Confirm — it simply leaves the order
            // Pending, so this reads as informational ("Balance due"), not an error.
            changeDueLabel.setText((enough ? "Change due: " : "Balance due: ") + amount.format());
            changeDueLabel.setForeground(enough ? Theme.STATUS_COMPLETED : Theme.STATUS_CANCELLED);
        }
        updateConfirmEnabled();
    }

    private void updateConfirmEnabled() {
        confirmButton.setEnabled(!submitting && draft.canConfirm());
    }

    // -------------------------------------------------------------- ticket rows (inline steppers)

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

    private static JLabel emptyStateLabel() {
        JLabel l = UiFactory.muted("Tap an item to add it to the order.");
        l.setBorder(BorderFactory.createEmptyBorder(24, 20, 24, 20));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
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
            BorderFactory.createEmptyBorder(12, 20, 12, 20)));

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        JLabel name = UiFactory.labelBold(line.displayName());
        JLabel unit = UiFactory.muted(line.unitPrice().format() + " each");
        info.add(name);
        info.add(unit);
        if (hasOption) {
            JLabel option = UiFactory.muted(line.optionGroupName() + ": " + line.optionValueName());
            info.add(option);
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

    private static JButton confirmButtonStyled() {
        JButton b = UiFactory.primaryButton("Process Payment");
        b.setIcon(rps.ui.icon.LineIcon.CONFIRM.of(16, Theme.ON_PRIMARY));
        b.setIconTextGap(9);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        b.setFont(Theme.FONT_HEADING);
        return b;
    }

    // -------------------------------------------------------------- confirm flow

    private void confirmOrder() {
        if (submitting) return;          // belt-and-braces against a queued second click
        submitting = true;
        updateConfirmEnabled();

        // Snapshot on the EDT: the save runs on a background thread, and until Busy's
        // glass pane appears (150ms) the cashier can still tap menu tiles. Passing the
        // live draft let those later additions land in the saved order.
        OrderDraft confirmed = draft.snapshot();

        Busy.call(this, () -> orderDao.saveOrder(confirmed, session.staffId(), session.staffName()))
            .message("Saving order…")
            .onSuccess(saved -> {
                submitting = false;
                receiptPrinter.printBothAsync(saved);
                backupService.requestBackup();
                showOrderConfirmedDialog(saved);
                resetDraft();
            })
            .onError(e -> {
                submitting = false;
                JOptionPane.showMessageDialog(this, e.getMessage(), "Could not save order", JOptionPane.ERROR_MESSAGE);
                updateConfirmEnabled();
            })
            .start();
    }

    private void showOrderConfirmedDialog(Order saved) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Order Confirmed",
            Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(Theme.SURFACE);

        JPanel content = new JPanel();
        content.setBackground(Theme.SURFACE);
        content.setBorder(BorderFactory.createEmptyBorder(28, 36, 24, 36));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JComponent badge = new CheckBadge();
        badge.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(badge);

        content.add(Box.createVerticalStrut(Theme.SPACE_MD));
        JLabel heading = UiFactory.title("Order Confirmed");
        heading.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(heading);

        content.add(Box.createVerticalStrut(Theme.SPACE_XS));
        JLabel orderNum = UiFactory.labelBold(saved.orderNumber());
        orderNum.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(orderNum);

        content.add(Box.createVerticalStrut(Theme.SPACE_XS));
        String changeText = saved.totals().hasCashTendered()
            ? "  ·  Change " + saved.totals().changeDue().format() : "";
        JLabel total = UiFactory.muted("Total " + saved.totals().total().format() + changeText);
        total.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(total);

        content.add(Box.createVerticalStrut(Theme.SPACE_XS));
        JLabel status = UiFactory.labelBold(saved.status().label()
            + (saved.status().awaitsPayment()
                ? "  ·  Balance due " + saved.totals().balanceDue().format() : ""));
        status.setForeground(saved.status() == rps.model.OrderStatus.PAYMENT_RECEIVED
            ? Theme.STATUS_COMPLETED : Theme.STATUS_CANCELLED);
        status.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(status);

        content.add(Box.createVerticalStrut(Theme.SPACE_LG));
        JButton ok = UiFactory.primaryButton("Start Next Order");
        ok.setAlignmentX(Component.CENTER_ALIGNMENT);
        ok.addActionListener(e -> dialog.dispose());
        content.add(ok);

        dialog.add(content, BorderLayout.CENTER);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.setPreferredSize(new Dimension(340, 340));
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** A filled circle with a checkmark, drawn directly rather than faked with borders. */
    private static final class CheckBadge extends JComponent {
        private static final int SIZE = 56;

        CheckBadge() {
            setPreferredSize(new Dimension(SIZE, SIZE));
            setMaximumSize(new Dimension(SIZE, SIZE));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.STATUS_COMPLETED);
            g2.fillOval(0, 0, SIZE, SIZE);

            g2.setColor(Theme.ON_PRIMARY);
            g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int[] xs = {SIZE / 4, SIZE / 2 - 2, (SIZE * 3) / 4};
            int[] ys = {SIZE / 2, SIZE * 2 / 3 - 2, SIZE / 3 - 2};
            g2.drawPolyline(xs, ys, 3);
            g2.dispose();
        }
    }

    /** Rebuilds the draft from scratch via OrderDraft.reset() rather than clearing fields
     *  one by one here — that pattern is exactly how a discount or cash value would leak
     *  into the next customer's order if a field were ever added and this call site
     *  forgotten. Every UI control is reset to match. */
    private void resetDraft() {
        draft.reset();
        tableField.setText("");
        phoneField.setText("");
        addressField.setText("");
        notesField.setText("");
        discountNoneBtn.setSelected(true);
        discountValueField.setText("");
        discountValueRow.setVisible(false);
        discountAmountLabel.setText(" ");
        cashTenderedField.setText("");
        changeDueLabel.setText(" ");
        updateConditionalFields();
        refreshTicket();
    }

    /** Cuts a long deal name down to something that fits the fixed-height tile, breaking
     *  on a word boundary where possible rather than mid-word. */
    private static String tileDisplayName(String name) {
        if (name.length() <= 60) return name;
        int cut = name.lastIndexOf(' ', 57);
        if (cut < 20) cut = 57;
        return name.substring(0, cut).stripTrailing() + "…";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @FunctionalInterface
    private interface SimpleDocListener extends javax.swing.event.DocumentListener {
        void onChange();
        @Override default void insertUpdate(javax.swing.event.DocumentEvent e) { onChange(); }
        @Override default void removeUpdate(javax.swing.event.DocumentEvent e) { onChange(); }
        @Override default void changedUpdate(javax.swing.event.DocumentEvent e) { onChange(); }
    }
}
