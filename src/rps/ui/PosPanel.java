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
import javax.swing.border.Border;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // A single scrollable row of underline text tabs (see categoryTab) with arrow
    // buttons either side — matches a POS category strip rather than the wrapping pill
    // chips this used to be; with up to ~17 categories, one scrollable row reads far
    // closer to a real till than several rows of chips ever did.
    private final JPanel categoryStrip = new JPanel();
    private final JScrollPane categoryScrollPane = new JScrollPane(categoryStrip,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    private final JTextField menuSearchField = UiFactory.textField(18);
    private final WrapPanel tileGrid = new WrapPanel(new WrapLayout(FlowLayout.LEFT, 14, 14));
    private final JPanel ticketList = new JPanel();
    private final JLabel fieldsError = UiFactory.errorText(" ");

    private final JTextField tableField = UiFactory.textField(8);
    private final JComponent tableRow = labeledField("Table number *", tableField);
    private final JTextField phoneField = UiFactory.textField(16);
    private final JLabel phoneLabel = UiFactory.muted("Phone number *");
    private final JComponent phoneRow = labeledFieldWithLabel(phoneLabel, phoneField);
    /** Unticked by default — the ordinary phone requirement for Takeaway/Delivery still
     *  applies unless the cashier explicitly ticks this for a customer who won't give a
     *  number. Lives directly under the phone field, since it only makes sense in
     *  relation to it. */
    private final JCheckBox noPhoneCheck = new JCheckBox("No phone number");
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

    /** The bottom action bar: a single burgundy bar carrying both the confirm action and
     *  the running total, in place of a separate "Total" row + Confirm button. */
    private final PayBar payBar = new PayBar();
    /** True from the moment Confirm is clicked until the save resolves. Confirm is
     *  re-enabled by updateConfirmEnabled(), which is reached from refreshTicket() /
     *  refreshTotals() / validateFields() — so merely disabling the button in the click
     *  handler was not enough: adding a ticket line during the save re-enabled it and a
     *  second click submitted the same order again (reproduced against the real panel).
     *  This flag is the authoritative gate; the bar's clickable state is derived from it. */
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

    // Decoded tile icons, keyed by menu item id. Kept across category switches and
    // searches (rebuildTileGrid runs on every one of those, and re-fetching a photo's
    // bytes from the database on every click would be a needless round trip) but wiped
    // in applyReloadedMenu — the one place both the initial load and a poll-detected
    // catalog change funnel through — so a manager who just changed an item's photo in
    // the Menu tab sees the new one here without restarting the app.
    private final Map<Integer, ImageIcon> tileImageCache = new HashMap<>();

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
        menuSearchField.putClientProperty("JTextField.leadingIcon",
            rps.ui.icon.LineIcon.SEARCH.of(16, Theme.TEXT_MUTED));
        menuSearchField.getDocument().addDocumentListener((SimpleDocListener) () -> {
            searchQuery = menuSearchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
            rebuildTileGrid();
        });
        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setOpaque(false);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.add(menuSearchField, BorderLayout.CENTER);
        top.add(searchRow);
        top.add(Box.createVerticalStrut(14));

        top.add(buildCategoryRow());
        left.add(top, BorderLayout.NORTH);

        tileGrid.setOpaque(false);
        // Covers the gaps between tiles — each tile handles its own drag/tap (buildTile),
        // but the WrapLayout gaps and any margin beyond the last tile belong to tileGrid
        // itself and would otherwise be a dead patch a swipe can't start from.
        TouchScroll.install(tileGrid);
        JScrollPane scroll = new JScrollPane(tileGrid,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        left.add(scroll, BorderLayout.CENTER);

        return left;
    }

    /** Left/right arrows either side of a single-row, horizontally scrolling category
     *  strip — a shop with ~17 categories cannot fit them all in one screen width, and a
     *  scrollable row with explicit arrows reads as a considered POS category rail rather
     *  than the wrapping multi-row pill grid this replaces. */
    private JComponent buildCategoryRow() {
        categoryStrip.setLayout(new BoxLayout(categoryStrip, BoxLayout.X_AXIS));
        categoryStrip.setOpaque(false);
        TouchScroll.install(categoryStrip);
        categoryScrollPane.setBorder(null);
        categoryScrollPane.setOpaque(false);
        categoryScrollPane.getViewport().setOpaque(false);
        categoryScrollPane.setPreferredSize(new Dimension(10, 42));
        categoryScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));

        JButton leftArrow = scrollArrowButton(rps.ui.icon.LineIcon.CHEVRON_LEFT);
        JButton rightArrow = scrollArrowButton(rps.ui.icon.LineIcon.CHEVRON_RIGHT);
        leftArrow.addActionListener(e -> scrollCategories(-160));
        rightArrow.addActionListener(e -> scrollCategories(160));

        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        row.add(leftArrow, BorderLayout.WEST);
        row.add(categoryScrollPane, BorderLayout.CENTER);
        row.add(rightArrow, BorderLayout.EAST);
        return row;
    }

    private static JButton scrollArrowButton(rps.ui.icon.LineIcon icon) {
        JButton b = new JButton(icon.of(13, Theme.TEXT_MUTED));
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setMargin(new Insets(6, 6, 6, 6));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private void scrollCategories(int delta) {
        JScrollBar bar = categoryScrollPane.getHorizontalScrollBar();
        bar.setValue(bar.getValue() + delta);
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
        tileImageCache.clear();

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
            JToggleButton btn = categoryTab(c.name(), c.equals(activeCategory));
            btn.addActionListener(e -> {
                activeCategory = c;
                rebuildCategoryStrip();
                rebuildTileGrid();
            });
            // Tabs span nearly the entire visible strip (there is almost no bare gap
            // between them), so a horizontal swipe to scroll categories is overwhelmingly
            // likely to start ON a tab. A JToggleButton fires its own ActionListener
            // through its own ButtonModel, which a plain added-on listener can't gate the
            // way TouchScroll.install(..., onTap) gates a raw click — installOnButton
            // disarms the button's model directly the instant a drag is detected instead.
            TouchScroll.installOnButton(btn);
            group.add(btn);
            categoryStrip.add(btn);
        }
        categoryStrip.revalidate();
        categoryStrip.repaint();
    }

    /** Plain text tab with a bottom underline when selected — replaces the pill-chip look
     *  category selectors use elsewhere in the app, matching a POS category rail instead. */
    private static JToggleButton categoryTab(String text, boolean selected) {
        JToggleButton b = new JToggleButton(text);
        b.setSelected(selected);
        b.setFont(selected ? Theme.FONT_BODY_BOLD : Theme.FONT_BODY);
        b.setForeground(selected ? Theme.PRIMARY : Theme.TEXT_MUTED);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // The underline sits at the very bottom edge (outer border) with padding inside
        // it around the text — reserved even when unselected (same color as the strip's
        // own background) so a tab's width never shifts by 3px the moment it's picked.
        Border underline = BorderFactory.createMatteBorder(0, 0, 3, 0,
            selected ? Theme.PRIMARY : Theme.BACKGROUND);
        b.setBorder(BorderFactory.createCompoundBorder(underline,
            BorderFactory.createEmptyBorder(8, 12, 5, 12)));
        return b;
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

    /** Tile geometry, shared between the tile's own size and the exact box a cover-cropped
     *  photo is rendered at — the tile is a fixed size (WrapLayout needs one to lay the
     *  grid out), so the photo can be sized once, up front, rather than resized per-layout. */
    private static final int TILE_WIDTH = 182;
    private static final int TILE_IMAGE_HEIGHT = 108;

    private JComponent buildTile(MenuItem item) {
        RoundedPanel tile = new RoundedPanel(new BorderLayout());
        tile.setPreferredSize(new Dimension(TILE_WIDTH, TILE_IMAGE_HEIGHT + 78));
        tile.setBackground(Theme.SURFACE);
        tile.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1, true));
        tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JComponent image = buildTileImageArea(item);
        tile.add(image, BorderLayout.NORTH);

        // Deal names carry their full contents (e.g. "Heavy Deal: 2 Chicken Burger + ...")
        // so the kitchen ticket — which prints whatever the item's name is — shows exactly
        // what's included with no separate schema for bundle contents. That makes some
        // names too long for this fixed-height tile, so the tile itself shows a shortened
        // form with the full text as a tooltip; the order always uses the real item, never
        // the truncated label.
        JPanel nameWrap = new JPanel(new BorderLayout());
        nameWrap.setOpaque(true);
        nameWrap.setBackground(Theme.SURFACE);
        nameWrap.setBorder(BorderFactory.createEmptyBorder(9, 11, 6, 11));
        JLabel name = new JLabel("<html>" + escape(tileDisplayName(item.name())) + "</html>");
        name.setFont(Theme.FONT_BODY_BOLD);
        name.setForeground(Theme.TEXT);
        name.setVerticalAlignment(SwingConstants.TOP);
        if (item.name().length() > 60) {
            name.setToolTipText(escape(item.name()));
            tile.setToolTipText(escape(item.name()));
        }
        nameWrap.add(name, BorderLayout.CENTER);
        tile.add(nameWrap, BorderLayout.CENTER);

        // A dark price bar spanning the tile's full width, at the very bottom — distinct
        // from the plain in-line price text this replaces, and the one place on the tile
        // that always carries the brand's own burgundy regardless of whether the item has
        // a photo.
        String priceText = item.sized() ? "from " + item.lowestPrice().format() : item.singlePrice().format();
        JPanel priceBar = new JPanel(new BorderLayout());
        priceBar.setOpaque(true);
        priceBar.setBackground(Theme.PRIMARY_DARK);
        priceBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel price = new JLabel(priceText);
        price.setFont(Theme.FONT_BODY_BOLD);
        price.setForeground(Theme.ON_PRIMARY);
        priceBar.add(price, BorderLayout.WEST);
        tile.add(priceBar, BorderLayout.SOUTH);

        java.awt.event.MouseAdapter hover = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                tile.setBorder(BorderFactory.createLineBorder(Theme.PRIMARY, 2, true));
            }
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                tile.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1, true));
            }
        };
        // Tap adds the item; a drag past a small threshold scrolls the menu grid instead
        // and the tap never fires — the till is touchscreen, and reaching the actual
        // scrollbar thumb with a fingertip is impractical, so any swipe across the grid
        // (including one that starts on a tile, which is most of the visible area) has to
        // scroll rather than silently add whatever tile the finger happened to land on.
        Runnable tap = () -> onItemPicked(item);
        for (Component c : new Component[]{tile, name, nameWrap, priceBar, price, image}) {
            c.addMouseListener(hover);
            TouchScroll.install((JComponent) c, tap);
        }

        return tile;
    }

    /** A JPanel whose paint() clips its children to a rounded rectangle — without this,
     *  the square image label and price bar inside would paint right up to their own
     *  square corners regardless of the tile's own rounded border, breaking the rounded
     *  card look right where it matters most (the photo's top corners). */
    private static final class RoundedPanel extends JPanel {
        private static final int ARC = 14;

        RoundedPanel(LayoutManager lm) {
            super(lm);
        }

        @Override
        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setClip(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), ARC, ARC));
            super.paint(g2);
            g2.dispose();
        }
    }

    /** The image area starts blank (or with a cached icon already in hand) and is filled
     *  in by a background fetch — MenuDao.loadImage is a real DB round trip, and doing
     *  that on the EDT while a whole grid of tiles is being built would visibly stall the
     *  New Order screen every time the catalog changes. An item with no uploaded photo
     *  falls back to a tinted panel with its category's icon centered, so every tile in
     *  the grid keeps the same silhouette regardless of which items have real photos. */
    private JComponent buildTileImageArea(MenuItem item) {
        JLabel imageLabel = new JLabel();
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imageLabel.setVerticalAlignment(SwingConstants.CENTER);
        imageLabel.setOpaque(true);
        imageLabel.setPreferredSize(new Dimension(TILE_WIDTH, TILE_IMAGE_HEIGHT));

        if (!item.hasImage()) {
            imageLabel.setBackground(Theme.PRIMARY_TINT);
            rps.ui.icon.LineIcon icon = rps.ui.icon.LineIcon.forCategory(categoryNameFor(item));
            if (icon != null) imageLabel.setIcon(icon.of(40, Theme.PRIMARY));
            return imageLabel;
        }

        imageLabel.setBackground(Theme.BACKGROUND);
        ImageIcon cached = tileImageCache.get(item.id());
        if (cached != null) {
            imageLabel.setIcon(cached);
        } else {
            new SwingWorker<byte[], Void>() {
                @Override
                protected byte[] doInBackground() throws Exception {
                    return menuDao.loadImage(item.id());
                }

                @Override
                protected void done() {
                    try {
                        byte[] bytes = get();
                        // -2px accounts for the tile's own 1px border on each side, so the
                        // cropped photo lands flush with the card edge rather than one
                        // pixel short of it.
                        ImageIcon icon = rps.util.ImageUtil.toIconCover(
                            bytes, TILE_WIDTH - 2, TILE_IMAGE_HEIGHT);
                        if (icon != null) {
                            tileImageCache.put(item.id(), icon);
                            imageLabel.setIcon(icon);
                        }
                    } catch (Exception ignore) {
                        // A tile that fails to load its photo just shows blank rather
                        // than the category placeholder — the manager will notice and can
                        // re-upload; it is not worth an error dialog on a 5s poll cycle.
                    }
                }
            }.execute();
        }
        return imageLabel;
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
        TouchScroll.install(ticketList);

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
        TouchScroll.install(column);
        column.add(ticketList);
        column.add(buildOrderDetailsSection());

        JScrollPane scroll = new JScrollPane(column,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        right.add(scroll, BorderLayout.CENTER);

        // Pinned outside the scrollable column, not inside it — the whole point of a
        // fixed pay bar is that it never needs to be scrolled to, however long the ticket
        // or the order-details form above it gets.
        right.add(buildFooter(), BorderLayout.SOUTH);
        return right;
    }

    /** The running totals just above the pay bar (discount/delivery-fee/change-or-balance)
     *  plus the bar itself — everything a cashier needs to see immediately before
     *  confirming, all fixed to the bottom of the screen. */
    private JComponent buildFooter() {
        JPanel footer = new JPanel();
        footer.setOpaque(true);
        footer.setBackground(Theme.SURFACE);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));

        JPanel breakdown = new JPanel();
        breakdown.setOpaque(false);
        breakdown.setLayout(new BoxLayout(breakdown, BoxLayout.Y_AXIS));
        breakdown.setBorder(BorderFactory.createEmptyBorder(12, 20, 4, 20));
        discountAmountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        breakdown.add(discountAmountLabel);
        deliveryFeeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        breakdown.add(deliveryFeeLabel);
        changeDueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        breakdown.add(changeDueLabel);
        footer.add(breakdown);

        payBar.setActionText("Confirm Order");
        payBar.setOnClick(this::confirmOrder);
        footer.add(payBar);
        return footer;
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

        noPhoneCheck.setOpaque(false);
        noPhoneCheck.setFont(Theme.FONT_SMALL);
        noPhoneCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        noPhoneCheck.addActionListener(e -> validateFields());
        panel.add(noPhoneCheck);

        panel.add(Box.createVerticalStrut(10));
        addressRow.setVisible(false);
        panel.add(addressRow);
        addressField.getDocument().addDocumentListener((SimpleDocListener) this::validateFields);
        // deliveryFeeLabel now lives in the fixed footer alongside the other running
        // totals (buildFooter), not here — it is still the exact same JLabel instance
        // refreshTotals() updates, just displayed lower on the screen.

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
        // discountAmountLabel is shown in the fixed footer, not here — same instance,
        // just lower on the screen alongside the other running totals.
        discountValueField.getDocument().addDocumentListener((SimpleDocListener) this::onDiscountChanged);

        panel.add(Box.createVerticalStrut(14));
        panel.add(divider());
        panel.add(Box.createVerticalStrut(14));

        panel.add(sectionLabel("CASH"));
        panel.add(Box.createVerticalStrut(8));
        panel.add(labeledField("Cash Paid (leave blank if unpaid)", cashTenderedField));
        cashTenderedField.getDocument().addDocumentListener((SimpleDocListener) this::onCashChanged);
        // changeDueLabel and the running total now live in the fixed footer (buildFooter)
        // rather than at the end of this scrollable section — see MainWindow-style
        // "pinned bottom bar" reasoning there.

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
        validateFields(); // also sets phoneLabel's text, accounting for noPhoneCheck
        refreshTotals();
        revalidate();
        repaint();
    }

    private void validateFields() {
        draft.setCustomerPhone(phoneField.getText());
        draft.setDeliveryAddress(addressField.getText());
        draft.setTableNumber(tableField.getText());
        draft.setPhoneNotRequired(noPhoneCheck.isSelected());

        boolean isDineIn = draft.type() == OrderType.DINE_IN;
        boolean isDelivery = draft.type() == OrderType.DELIVERY;
        boolean phoneBlank = phoneField.getText().isBlank();
        boolean phoneOk = Validators.isValidPakistaniPhone(phoneField.getText());
        boolean phoneRequired = !isDineIn && !noPhoneCheck.isSelected();
        phoneLabel.setText(phoneRequired ? "Phone number *" : "Phone number (optional)");

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
        payBar.setAmount(draft.estimatedTotal().format());
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
        payBar.setPayEnabled(!submitting && draft.canConfirm());
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
        // The name/price area has no tap action of its own, so a touchscreen swipe that
        // starts here (very likely — it's most of the row) needs to scroll the ticket
        // rather than do nothing. The +/-/× steppers are deliberately left alone: they're
        // small, precise controls meant to be tapped exactly, not swiped over.
        TouchScroll.install(row);

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        TouchScroll.install(info);
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

    /**
     * A single full-width burgundy bar carrying both the confirm action and the running
     * total — "Confirm Order" on the left, the amount on the right — in place of a
     * separate "Total" row above a plain button. Not a JButton: a button paints its own
     * background across its own bounds only, and getting a two-part label (static action
     * text + a right-aligned amount that changes independently) onto one JButton without
     * fighting its own layout would need more surgery than a plain clickable JPanel does.
     */
    private static final class PayBar extends JPanel {
        private final JLabel actionLabel = new JLabel();
        private final JLabel amountLabel = new JLabel();
        private boolean payEnabled = true;
        private Runnable onClick = () -> {};

        PayBar() {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            actionLabel.setFont(Theme.FONT_HEADING);
            actionLabel.setIcon(rps.ui.icon.LineIcon.CONFIRM.of(17, Theme.ON_PRIMARY));
            actionLabel.setIconTextGap(10);
            amountLabel.setFont(Theme.FONT_TITLE);
            add(actionLabel, BorderLayout.WEST);
            add(amountLabel, BorderLayout.EAST);
            applyColors();

            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (payEnabled) onClick.run();
                }
                @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                    if (payEnabled) setBackground(Theme.PRIMARY_DARK);
                }
                @Override public void mouseExited(java.awt.event.MouseEvent e) {
                    applyColors();
                }
            });
        }

        void setActionText(String text) {
            actionLabel.setText(text);
        }

        void setAmount(String amount) {
            amountLabel.setText(amount);
        }

        void setOnClick(Runnable r) {
            onClick = r;
        }

        void setPayEnabled(boolean enabled) {
            payEnabled = enabled;
            applyColors();
            setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        private void applyColors() {
            setOpaque(true);
            setBackground(payEnabled ? Theme.PRIMARY : Theme.BORDER);
            Color fg = payEnabled ? Theme.ON_PRIMARY : Theme.TEXT_MUTED;
            actionLabel.setForeground(fg);
            amountLabel.setForeground(fg);
        }
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
                receiptPrinter.printKitchenTicketsAsync(saved);
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
        JLabel status = UiFactory.labelBold(saved.paymentStatus().label()
            + (saved.paymentStatus().awaitsPayment()
                ? "  ·  Balance due " + saved.totals().balanceDue().format() : ""));
        status.setForeground(saved.paymentStatus().isPaid()
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
        noPhoneCheck.setSelected(false);
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
