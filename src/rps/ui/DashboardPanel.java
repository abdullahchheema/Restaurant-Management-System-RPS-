package rps.ui;

import rps.app.Session;
import rps.db.Db;
import rps.db.DatabaseException;
import rps.db.OrderDao;
import rps.model.Order;
import rps.model.FulfilmentStatus;
import rps.model.PaymentStatus;
import rps.model.OrderType;
import rps.print.ReceiptPrinter;
import rps.ui.icon.LineIcon;
import rps.ui.theme.EmptyState;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.CsvExport;
import rps.util.Money;
import rps.util.Validators;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DashboardPanel extends JPanel {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH);
    private static final int POLL_MS = 4000;

    private static final int COL_TOTAL = 4;
    // One column for both tracks: shows the payment pill (Unpaid/Partially Paid/Paid),
    // or a Cancelled pill overriding that when the order is cancelled — a customer only
    // ever needs to know "is this order still live, and if so has it been paid", and a
    // separate always-visible Pending/Completed indicator was more than the counter
    // actually uses day to day. Clicking it still reaches every action (Record Payment,
    // Cancel/Force Cancel) — see showStatusMenu.
    private static final int COL_STATUS = 5;
    private static final int COL_ACTION = 7;

    private final Db db;
    private final Session session;
    private final OrderDao orderDao;
    private final ReceiptPrinter receiptPrinter = new ReceiptPrinter();

    // Single-view table: everything matching the current filter, used when no split is
    // in effect (an explicit status filter is chosen, or "All" happens to have zero
    // unpaid orders right now).
    private final OrdersTableModel allModel = new OrdersTableModel();
    private final JTable allTable = buildOrdersTable(allModel);

    // Split view: everything still awaiting payment (Pending + Partially Paid) on the
    // left, everything settled or dead (Payment Received + Cancelled) on the right —
    // only shown when the status filter is "All" AND there is at least one unpaid
    // order to show.
    private final OrdersTableModel pendingModel = new OrdersTableModel();
    private final JTable pendingTable = buildOrdersTable(pendingModel);
    private final OrdersTableModel settledModel = new OrdersTableModel();
    private final JTable settledTable = buildOrdersTable(settledModel);
    private final JLabel pendingHeading = splitHeading("Unpaid");
    private final JLabel settledHeading = splitHeading("Completed");

    private final JLabel refreshingDot = refreshingDotStyled();

    private final JLabel totalOrdersValue = statCardValueLabel();
    private final JLabel revenueValue = statCardValueLabel();
    private final JLabel outstandingValue = statCardValueLabel();

    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerHolder = new JPanel(centerCards);
    private final EmptyState emptyState = new EmptyState(LineIcon.DINE_IN, "No orders yet today", " ");
    private static final String CARD_TABLE = "table";
    private static final String CARD_SPLIT = "split";
    private static final String CARD_EMPTY = "empty";

    private static JLabel statCardValueLabel() {
        JLabel l = new JLabel("—");
        l.setFont(Theme.FONT_BRAND.deriveFont(Font.BOLD, 26f));
        l.setForeground(Theme.PRIMARY);
        return l;
    }

    /** A small dot that appears only while the 4s poll's SwingWorker is in flight —
     *  non-blocking, no overlay, reserved space so it never shifts layout. */
    private static JLabel refreshingDotStyled() {
        JLabel l = new JLabel("● Refreshing");
        l.setFont(Theme.FONT_SMALL);
        l.setForeground(Theme.ACCENT_DEEP);
        l.setVisible(false);
        return l;
    }

    private static JLabel splitHeading(String text) {
        JLabel l = UiFactory.heading(text);
        l.setBorder(BorderFactory.createEmptyBorder(0, 4, 8, 0));
        return l;
    }

    private final JComboBox<String> typeFilter = new JComboBox<>(new String[]{"All", "Dine-in", "Takeaway", "Delivery"});
    // One filter for the one column now shown: three payment states plus Cancelled,
    // which is a fulfilment state but sits in the same list here since it's what that
    // column's "Cancelled" pill corresponds to — see OrdersTableModel#getValueAt.
    private final JComboBox<String> statusFilter = new JComboBox<>(
        new String[]{"All", "Unpaid", "Partially Paid", "Paid", "Cancelled"});
    private final DateRangePicker dateRangePicker =
        new DateRangePicker(OrderDao.businessDate(ZonedDateTime.now()), OrderDao.businessDate(ZonedDateTime.now()));

    private final Timer pollTimer;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private String lastFingerprint = "";
    private List<OrderDao.OrderRow> lastLoadedRows = List.of();

    public DashboardPanel(Db db, Session session) {
        this.db = db;
        this.session = session;
        this.orderDao = new OrderDao(db);

        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG));

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        pollTimer = new Timer(POLL_MS, e -> refresh(false));
        pollTimer.setRepeats(true);

        dateRangePicker.onApply((f, t) -> refresh(true));

        refresh(true);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        pollTimer.start();
    }

    @Override
    public void removeNotify() {
        pollTimer.stop();
        super.removeNotify();
    }

    private JComponent buildTop() {
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel statsRow = new JPanel(new GridLayout(1, 3, 16, 0));
        statsRow.setOpaque(false);
        statsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        statsRow.add(statCard("Total Orders", totalOrdersValue));
        statsRow.add(statCard("Revenue", revenueValue));
        statsRow.add(statCard("Outstanding (Unpaid)", outstandingValue));
        top.add(statsRow);

        top.add(Box.createVerticalStrut(Theme.SPACE_LG));

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        filterRow.setOpaque(false);
        filterRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterRow.add(labeledFilter("Order Type", typeFilter));
        filterRow.add(labeledFilter("Status", statusFilter));
        filterRow.add(labeledFilter("Date Range", dateRangePicker));

        // Both combos refresh immediately on change — the date range already applies
        // itself the moment a preset or a custom range is picked, so a separate "Apply"
        // button would be the odd one out rather than a consistent control.
        typeFilter.addActionListener(e -> refresh(true));
        statusFilter.addActionListener(e -> refresh(true));

        JButton exportBtn = UiFactory.secondaryButton("Export to CSV");
        exportBtn.addActionListener(e -> exportCsv());
        JPanel actionButtons = new JPanel();
        actionButtons.setOpaque(false);
        actionButtons.setLayout(new BoxLayout(actionButtons, BoxLayout.Y_AXIS));
        actionButtons.add(Box.createVerticalStrut(20));
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actionRow.setOpaque(false);
        actionRow.add(exportBtn);
        actionRow.add(refreshingDot);
        actionButtons.add(actionRow);
        filterRow.add(actionButtons);

        top.add(filterRow);

        return top;
    }

    /** Card label above, large bold number below — matches the app's Card elevation
     *  treatment rather than the plain inline summary line this replaces. */
    private JComponent statCard(String label, JLabel value) {
        rps.ui.theme.Card card = new rps.ui.theme.Card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        JLabel labelComp = UiFactory.muted(label);
        labelComp.setAlignmentX(Component.LEFT_ALIGNMENT);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(labelComp);
        card.add(Box.createVerticalStrut(10));
        card.add(value);
        return card;
    }

    private static JComponent labeledFilter(String labelText, JComponent field) {
        JPanel wrap = new JPanel(new BorderLayout(0, 6));
        wrap.setOpaque(false);
        JLabel label = UiFactory.label(labelText);
        wrap.add(label, BorderLayout.NORTH);
        wrap.add(field, BorderLayout.CENTER);
        return wrap;
    }

    // -------------------------------------------------------------- center: table(s)

    private JComponent buildCenter() {
        centerHolder.setOpaque(false);
        centerHolder.add(scrollNoHorizontal(allTable), CARD_TABLE);
        centerHolder.add(buildSplitCard(), CARD_SPLIT);
        centerHolder.add(emptyState, CARD_EMPTY);
        return centerHolder;
    }

    private JComponent buildSplitCard() {
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(pendingHeading, BorderLayout.NORTH);
        left.add(scrollNoHorizontal(pendingTable), BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(settledHeading, BorderLayout.NORTH);
        right.add(scrollNoHorizontal(settledTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);
        split.setDividerSize(6);
        split.setBorder(null);
        split.setOpaque(false);
        return split;
    }

    /** Never scrolls horizontally — JTable's default AUTO_RESIZE_SUBSEQUENT_COLUMNS
     *  already keeps the table's width pinned to the viewport (columns shrink to fit
     *  rather than overflow), so this only needs to make sure the scrollbar policy
     *  itself can't kick in from a transient layout pass. */
    private static JScrollPane scrollNoHorizontal(JTable t) {
        JScrollPane scroll = new JScrollPane(t,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(Theme.SURFACE);
        return scroll;
    }

    /** Builds one fully-wired orders table: status pill (click to cancel / record
     *  payment), an Edit button at the end of every row, and a right-click menu for
     *  View Details / View Receipt / Reprint Receipts. Used for all three table
     *  instances (the single "All" view and both halves of the split view) so the
     *  interaction is identical no matter which pane a row is sitting in. */
    private JTable buildOrdersTable(OrdersTableModel model) {
        JTable t = new JTable(model);
        t.setFont(Theme.FONT_BODY);
        // Row height is Theme.ROW_HEIGHT globally via UIManager (Theme.install()) — not
        // re-set per panel, so it can't silently diverge from the other tables.
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setSelectionBackground(Theme.PRIMARY_TINT);
        t.setSelectionForeground(Theme.TEXT);
        t.setRowSelectionAllowed(true);
        // Applies to every column by class (all are Object.class here — the model
        // doesn't override getColumnClass) except Status and Action, whose per-column
        // renderers below take precedence over this default.
        t.setDefaultRenderer(Object.class, UiFactory.centeredCellRenderer());
        t.getColumnModel().getColumn(0).setCellRenderer(orderNumberCellRenderer());
        t.getColumnModel().getColumn(COL_STATUS).setCellRenderer(UiFactory.statusPillCellRenderer());
        t.getColumnModel().getColumn(COL_TOTAL).setCellRenderer(payableCellRenderer());

        // Fixed widths per column rather than leaving every column at JTable's default
        // 75px — at split-pane width (roughly half the window) eight equal columns left
        // "Order #" and "Staff" too narrow to show their own text, and the unconstrained
        // Edit button column got squeezed below what "Edit" needs to render, both
        // clipping to an ellipsis. Status gets the most room; Time / Items stay tight.
        // These are top-level requests, not guesses.
        setColumnWidth(t, 0, 68, 56);   // Order # (day sequence only, e.g. "007")
        setColumnWidth(t, 1, 56, 50);   // Time
        setColumnWidth(t, 2, 72, 60);   // Type
        setColumnWidth(t, 3, 48, 40);   // Items
        setColumnWidth(t, 4, 92, 80);   // Total
        setColumnWidth(t, 5, 132, 110); // Status
        setColumnWidth(t, 6, 92, 60);   // Staff

        var actionCol = t.getColumnModel().getColumn(COL_ACTION);
        actionCol.setCellRenderer(editButtonRenderer());
        actionCol.setCellEditor(editButtonEditor(model));
        actionCol.setResizable(false);
        actionCol.setMinWidth(64);
        actionCol.setMaxWidth(64);
        actionCol.setPreferredWidth(64);

        // Every row is one click, not two, and the SAME click both selects the row and
        // acts on whichever column was hit — no separate "select, then act" step:
        //   - Status pill  -> quick actions menu (Cancel / Record Payment)
        //   - Total (Pending order only) -> prompts to record a payment directly
        //   - Edit column  -> its own button (handled by the cell editor)
        //   - anything else -> opens the read-only detail view
        // Right-click also delivers a mouseClicked on some platforms, so this ignores
        // anything but the primary button to avoid opening details underneath the
        // context menu it's about to show. Wired through TouchScroll rather than a plain
        // click listener: the till is touchscreen, and a finger swiping down the table to
        // scroll it (very likely to start ON a row, which is most of the table) must not
        // also open whatever row it started on — a movement past the drag threshold pans
        // the table instead of firing this tap action.
        TouchScroll.install(t, (MouseEvent e) -> {
            if (!SwingUtilities.isLeftMouseButton(e)) return;
            int row = t.rowAtPoint(e.getPoint());
            int col = t.columnAtPoint(e.getPoint());
            if (row < 0) return;
            Order order = model.orderAt(row);
            if (col == COL_STATUS) {
                showStatusMenu(order, t, e.getPoint());
            } else if (col == COL_TOTAL && order.owesMoney()) {
                recordPaymentFor(order);
            } else if (col != COL_ACTION) {
                showOrderDetail(order.id());
            }
        });

        JPopupMenu menu = new JPopupMenu();
        JMenuItem detail = new JMenuItem("View Details");
        detail.addActionListener(e -> withSelectedRow(t, model, o -> showOrderDetail(o.id())));
        JMenuItem viewReceipt = new JMenuItem("View Receipt");
        viewReceipt.setIcon(LineIcon.PRINT.of(14, Theme.TEXT_MUTED));
        viewReceipt.addActionListener(e -> withSelectedRow(t, model, this::viewReceiptOf));
        JMenuItem reprint = new JMenuItem("Reprint Receipts");
        reprint.setIcon(LineIcon.PRINT.of(14, Theme.TEXT_MUTED));
        reprint.addActionListener(e -> withSelectedRow(t, model, this::reprintOrder));
        menu.add(detail);
        menu.add(viewReceipt);
        menu.add(reprint);

        // Right-clicking a row does NOT select it in Swing by default — without this,
        // the popup's actions would silently act on whatever row was last left-clicked
        // (or nothing, if none was), which looks exactly like "nothing happens".
        t.addMouseListener(new MouseAdapter() {
            private void selectRowUnderPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = t.rowAtPoint(e.getPoint());
                    if (row >= 0) t.setRowSelectionInterval(row, row);
                }
            }
            @Override public void mousePressed(MouseEvent e) { selectRowUnderPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { selectRowUnderPopup(e); }
        });
        t.setComponentPopupMenu(menu);

        return t;
    }

    private interface OrderAction {
        void accept(Order o) throws DatabaseException;
    }

    private void withSelectedRow(JTable t, OrdersTableModel model, OrderAction action) {
        int row = t.getSelectedRow();
        if (row < 0) return;
        try {
            action.accept(model.orderAt(row));
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void setColumnWidth(JTable t, int col, int preferred, int min) {
        var c = t.getColumnModel().getColumn(col);
        c.setPreferredWidth(preferred);
        c.setMinWidth(min);
    }

    /** Order # column: shows only the day's sequence number (see
     *  OrdersTableModel#displayOrderNumber), but a tooltip carries the real, full
     *  order number — needed the moment a date range spans more than one day, since
     *  "007" alone is then ambiguous between two different orders. */
    private static TableCellRenderer orderNumberCellRenderer() {
        return (table, value, isSelected, hasFocus, row, column) -> {
            Order order = ((OrdersTableModel) table.getModel()).orderAt(row);
            JLabel l = new JLabel(String.valueOf(value), SwingConstants.CENTER);
            l.setFont(Theme.FONT_BODY);
            l.setOpaque(true);
            l.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            l.setForeground(Theme.TEXT);
            l.setToolTipText(order.orderNumber());
            return l;
        };
    }

    /** Total column: plain centered text for a settled order, but a bordered "chip"
     *  look for a Pending order — a visual cue that this specific cell (unlike the rest
     *  of the row) responds to a click by prompting for a payment. */
    private static TableCellRenderer payableCellRenderer() {
        return (table, value, isSelected, hasFocus, row, column) -> {
            Order order = ((OrdersTableModel) table.getModel()).orderAt(row);
            JLabel l = new JLabel(String.valueOf(value), SwingConstants.CENTER);
            l.setFont(Theme.FONT_BODY);
            l.setOpaque(true);
            l.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            if (order.owesMoney()) {
                l.setForeground(Theme.STATUS_PENDING);
                l.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.STATUS_PENDING, 1, true),
                    BorderFactory.createEmptyBorder(2, 8, 2, 8)));
                l.setToolTipText("Click to record a payment");
            } else {
                l.setForeground(Theme.TEXT);
                l.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            }
            return l;
        };
    }

    /** Compact button style for a table cell — UiFactory.secondaryButton's margin
     *  (9/19px) is sized for a standalone toolbar button and doesn't fit inside a
     *  narrow table column without clipping to an ellipsis. */
    private static JButton compactTableButton(String text) {
        JButton b = new JButton(text);
        b.setFont(Theme.FONT_SMALL_BOLD);
        b.setForeground(Theme.TEXT);
        b.setBackground(Theme.SURFACE);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1, true));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** "Edit" while the order is still fully editable, "Add" once the edit window has
     *  closed and only new items can go on it — so the button says what it will actually
     *  let you do before it is clicked, rather than opening a dialog that turns out to be
     *  half locked. */
    private static TableCellRenderer editButtonRenderer() {
        return (table, value, isSelected, hasFocus, row, column) -> {
            JButton b = compactTableButton(String.valueOf(value));
            Order order = ((OrdersTableModel) table.getModel()).orderAt(row);
            b.setToolTipText(OrderDao.canEdit(order)
                ? "Edit this order"
                : "The edit window has passed — you can still add more items");
            return b;
        };
    }

    private TableCellEditor editButtonEditor(OrdersTableModel model) {
        return new EditButtonEditor(model);
    }

    /** An anonymous class can't both extend AbstractCellEditor and implement
     *  TableCellEditor in the same expression, so this is a named inner class instead —
     *  a JButton that fires Edit for whichever row it was last rendered into, then
     *  immediately stops "editing" so the button doesn't stay stuck in edit mode. */
    private final class EditButtonEditor extends AbstractCellEditor implements TableCellEditor {
        private final OrdersTableModel model;
        private final JButton button = compactTableButton("Edit");
        private Order currentOrder;

        EditButtonEditor(OrdersTableModel model) {
            this.model = model;
            button.addActionListener((ActionEvent e) -> {
                fireEditingStopped();
                editOrder(currentOrder);
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            currentOrder = model.orderAt(row);
            button.setText(String.valueOf(value));
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return currentOrder == null || OrderDao.canEdit(currentOrder) ? "Edit" : "Add";
        }
    }

    /** Every action the single Status/Payment pill can reach. "Mark Completed" is
     *  deliberately not offered here: fulfilment is no longer shown anywhere in this
     *  table (a live order just shows its payment state), so there is nothing on screen
     *  for that action to visibly change — the fulfilment column still exists in the
     *  data and still drives revenue exclusion once an order is cancelled, it is just
     *  not something the counter sets by hand any more. */
    private void showStatusMenu(Order order, Component invoker, Point p) {
        JPopupMenu menu = new JPopupMenu();
        if (order.owesMoney()) {
            JMenuItem recordPayment = new JMenuItem("Record Payment…");
            recordPayment.addActionListener(e -> recordPaymentFor(order));
            menu.add(recordPayment);

            JMenuItem payLater = new JMenuItem("Move to Pay Later…");
            payLater.addActionListener(e -> moveToLoan(order));
            menu.add(payLater);
        }
        if (order.fulfilmentStatus().isLive()) {
            // Inside the window this is the ordinary action; past it the same operation is
            // still possible but is deliberately relabelled, so nobody voids an hours-old
            // order believing it to be routine.
            if (OrderDao.withinCancelWindow(order)) {
                JMenuItem cancel = new JMenuItem("Cancel Order");
                cancel.addActionListener(e -> cancelOrder(order, false));
                menu.add(cancel);
            } else {
                JMenuItem force = new JMenuItem("Force Cancel…");
                force.addActionListener(e -> cancelOrder(order, true));
                menu.add(force);
            }
        }
        showOrNoActions(menu, invoker, p);
    }

    private static void showOrNoActions(JPopupMenu menu, Component invoker, Point p) {
        if (menu.getComponentCount() == 0) {
            JMenuItem none = new JMenuItem("No actions available");
            none.setEnabled(false);
            menu.add(none);
        }
        menu.show(invoker, p.x, p.y);
    }

    private void showOrderDetail(long orderId) {
        Busy.call(this, () -> orderDao.loadOrderForPrint(orderId))
            .message("Loading order…")
            .onSuccess(order -> {
                if (order != null) new OrderDetailDialog(this, order).setVisible(true);
            })
            .onError(e -> JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE))
            .start();
    }

    private void viewReceiptOf(Order orderRow) throws DatabaseException {
        Order full = orderDao.loadOrderForPrint(orderRow.id());
        if (full == null) return;
        new ReceiptPreviewDialog(this, full).setVisible(true);
    }

    private OrderDao.OrderFilter currentFilter() {
        OrderType type = switch ((String) typeFilter.getSelectedItem()) {
            case "Dine-in" -> OrderType.DINE_IN;
            case "Takeaway" -> OrderType.TAKEAWAY;
            case "Delivery" -> OrderType.DELIVERY;
            default -> null;
        };
        // "Cancelled" sets the fulfilment half of the filter; the three payment options
        // set the payment half — never both at once, since the single visible column
        // shows one or the other for any given row (see OrdersTableModel#getValueAt).
        String selected = (String) statusFilter.getSelectedItem();
        FulfilmentStatus fulfilment = "Cancelled".equals(selected) ? FulfilmentStatus.CANCELLED : null;
        PaymentStatus payment = switch (selected) {
            case "Unpaid" -> PaymentStatus.UNPAID;
            case "Partially Paid" -> PaymentStatus.PARTIALLY_PAID;
            case "Paid" -> PaymentStatus.PAID;
            default -> null;
        };
        return new OrderDao.OrderFilter(type, payment, fulfilment,
            dateRangePicker.from(), dateRangePicker.to());
    }

    private void refresh(boolean force) {
        if (!inFlight.compareAndSet(false, true)) return;
        OrderDao.OrderFilter filter = currentFilter();

        // Shown only if the poll takes long enough to notice — a short delay (like Busy's,
        // but lighter-weight since this indicator is non-blocking) so a normal sub-50ms
        // localhost round trip never flashes it four times a minute.
        Timer showRefreshing = new Timer(250, e -> refreshingDot.setVisible(true));
        showRefreshing.setRepeats(false);
        showRefreshing.start();

        SwingWorker<Object[], Void> worker = new SwingWorker<>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                String fp = orderDao.fingerprint(filter);
                if (!force && fp.equals(lastFingerprint)) {
                    return null;
                }
                lastFingerprint = fp;
                List<OrderDao.OrderRow> orders = orderDao.loadOrders(filter);
                OrderDao.DailySummary summary = orderDao.dailySummary(filter);
                return new Object[]{orders, summary};
            }

            @Override
            protected void done() {
                inFlight.set(false);
                showRefreshing.stop();
                refreshingDot.setVisible(false);
                try {
                    Object[] result = get();
                    if (result == null) return;
                    @SuppressWarnings("unchecked")
                    List<OrderDao.OrderRow> orders = (List<OrderDao.OrderRow>) result[0];
                    OrderDao.DailySummary summary = (OrderDao.DailySummary) result[1];
                    lastLoadedRows = orders;
                    totalOrdersValue.setText(String.valueOf(summary.orderCount()));
                    revenueValue.setText(summary.revenue().format());
                    outstandingValue.setText(summary.outstanding().format());
                    applyRows(orders, filter);
                } catch (Exception ex) {
                    // Transient poll failure: no error dialog on every 4s tick (that would
                    // be worse than silence), but the header's connection indicator (see
                    // MainWindow) is what actually surfaces a real outage to the user.
                    System.err.println("Dashboard refresh failed: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    /** Decides between the three center cards: empty state, a single full-width table,
     *  or the Pending | Completed split. The split only ever appears when the status
     *  dropdown is "All" (a specific status was not explicitly chosen) AND there is at
     *  least one Pending order to show — otherwise a second, always-empty "Pending"
     *  pane would be pure clutter. */
    private void applyRows(List<OrderDao.OrderRow> orders, OrderDao.OrderFilter filter) {
        if (orders.isEmpty()) {
            updateEmptyState(filter);
            return;
        }

        boolean statusFilterIsAll = filter.paymentStatus() == null && filter.fulfilmentStatus() == null;
        if (statusFilterIsAll) {
            List<OrderDao.OrderRow> pending = new ArrayList<>();
            List<OrderDao.OrderRow> settled = new ArrayList<>();
            for (OrderDao.OrderRow r : orders) {
                (r.order().owesMoney() ? pending : settled).add(r);
            }
            if (pending.isEmpty()) {
                allModel.setOrders(orders);
                centerCards.show(centerHolder, CARD_TABLE);
            } else {
                pendingHeading.setText("Unpaid (" + pending.size() + ")");
                settledHeading.setText("Settled (" + settled.size() + ")");
                pendingModel.setOrders(pending);
                settledModel.setOrders(settled);
                centerCards.show(centerHolder, CARD_SPLIT);
            }
        } else {
            allModel.setOrders(orders);
            centerCards.show(centerHolder, CARD_TABLE);
        }
    }

    private void updateEmptyState(OrderDao.OrderFilter filter) {
        LocalDate today = OrderDao.businessDate(ZonedDateTime.now());
        boolean isDefaultFilter = filter.type() == null && filter.paymentStatus() == null
            && filter.fulfilmentStatus() == null
            && filter.from().equals(today) && filter.to().equals(today);
        if (isDefaultFilter) {
            emptyState.setMessage("No orders yet today", "New orders will appear here as they come in.");
        } else {
            emptyState.setMessage("No orders match this filter", "Try widening the date range or clearing a filter.");
        }
        centerCards.show(centerHolder, CARD_EMPTY);
    }

    private void cancelOrder(Order order, boolean force) {
        if (order.fulfilmentStatus().isCancelled()) {
            JOptionPane.showMessageDialog(this, "This order is already cancelled.");
            return;
        }
        // Spelled out rather than a bare "are you sure": cancelling now DELETES the order
        // outright (see OrderDao#cancelOrder) rather than marking it Cancelled and keeping
        // the row, so this is the one chance to stop before the order and its full detail
        // are gone for good — no audit trail, nothing to undo from the Dashboard afterward.
        String paidWarning = order.paymentStatus().isPaid()
            ? "It has been PAID (" + order.totals().amountPaid().format()
              + ") — cancelling takes that back out of today's takings, so the money must be refunded.\n\n"
            : "";
        String deleteWarning = "This permanently deletes order " + order.orderNumber()
            + " and everything on it — this cannot be undone.\n\n";
        String prompt = force
            ? "Order " + order.orderNumber() + " is past the "
                + rps.util.AppSettings.get().orderCancelWindowMinutes()
                + "-minute cancellation window.\n\n" + paidWarning + deleteWarning + "Force cancel it anyway?"
            : deleteWarning + paidWarning + "Cancel order " + order.orderNumber() + "?";
        int confirm = JOptionPane.showConfirmDialog(this, prompt,
            force ? "Force Cancel" : "Confirm", JOptionPane.YES_NO_OPTION,
            force ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            boolean ok = orderDao.updateFulfilment(order.id(), order.fulfilmentStatus(),
                FulfilmentStatus.CANCELLED, session.staffId(), force);
            if (!ok) {
                JOptionPane.showMessageDialog(this,
                    "This order was already updated by another terminal. Refreshing.",
                    "Out of date", JOptionPane.WARNING_MESSAGE);
            }
            refresh(true);
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void recordPaymentFor(Order order) {
        if (!order.owesMoney()) {
            JOptionPane.showMessageDialog(this,
                "This order is already settled or cancelled — no payment can be recorded against it.");
            return;
        }
        Money balanceDue = order.totals().balanceDue();
        JTextField amountField = UiFactory.textField(10);
        // A shortcut for the common case — the customer paid exactly what's owed — so
        // the cashier doesn't have to retype the balance-due figure already shown right
        // above and risk a typo. It only fills the field; Record/OK still has to be
        // clicked, same as typing the amount by hand would.
        JButton fullPaymentBtn = UiFactory.secondaryButton("Full Payment Received");
        fullPaymentBtn.addActionListener(e -> {
            amountField.setText(balanceDue.asBigDecimal().toPlainString());
            amountField.requestFocusInWindow();
        });
        JPanel amountRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        amountRow.setOpaque(false);
        amountRow.add(amountField);
        amountRow.add(fullPaymentBtn);

        int choice = JOptionPane.showConfirmDialog(this,
            new Object[]{"Balance due: " + balanceDue.format(), "Amount received:", amountRow},
            "Record Payment", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        Money amount = Validators.parsePrice(amountField.getText());
        if (amount == null || !amount.isPositive()) {
            JOptionPane.showMessageDialog(this, "Enter a valid amount greater than zero.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            boolean ok = orderDao.recordPayment(order.id(), amount, session.staffId());
            if (!ok) {
                JOptionPane.showMessageDialog(this,
                    "This order was already updated by another terminal. Refreshing.",
                    "Out of date", JOptionPane.WARNING_MESSAGE);
            }
            // Payment amounts must be reflected immediately, not on the next 4s poll —
            // this both re-fetches the row (new status/amount_paid) and the summary
            // cards (Revenue / Outstanding) in one round trip.
            refresh(true);
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Hands the order over to the Pay Later ledger. Spelled out rather than confirmed with
     *  a bare "are you sure" because it has two consequences a cashier should not discover
     *  afterwards: the order leaves this screen entirely, and its total starts counting as
     *  revenue despite the cash not having arrived. */
    private void moveToLoan(Order order) {
        Money balance = order.totals().balanceDue();
        int confirm = JOptionPane.showConfirmDialog(this,
            "Put order " + order.orderNumber() + " on account (pay later)?\n\n"
                + "Still owed: " + balance.format() + "\n\n"
                + "It will move off the Dashboard into the Pay Later tab, where the balance\n"
                + "stays on record until the customer settles it. Its total counts as revenue\n"
                + "from now on, and it stops showing as outstanding.",
            "Move to Pay Later", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            if (!orderDao.moveToLoan(order.id(), session.staffId())) {
                JOptionPane.showMessageDialog(this,
                    "This order was already updated by another terminal. Refreshing.",
                    "Out of date", JOptionPane.WARNING_MESSAGE);
            }
            refresh(true);
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void editOrder(Order order) {
        if (order == null) return;
        // Past the edit window the dialog still opens — in add-only mode, where the placed
        // items are frozen but more can be ordered (OrderEditDialog / OrderDao.canAddItems).
        // Only a cancelled order has nothing left to do here at all.
        if (!OrderDao.canAddItems(order)) {
            JOptionPane.showMessageDialog(this,
                "This order was cancelled — it can no longer be edited or added to.");
            return;
        }
        Busy.call(this, () -> orderDao.loadOrderForPrint(order.id()))
            .message("Loading order…")
            .onSuccess(full -> {
                if (full == null) return;
                new OrderEditDialog(this, db, session, full, receiptPrinter, () -> refresh(true)).setVisible(true);
            })
            .onError(e -> JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE))
            .start();
    }

    private void reprintOrder(Order orderRow) throws DatabaseException {
        Order full = orderDao.loadOrderForPrint(orderRow.id());
        receiptPrinter.reprintAsync(full);
    }

    private void exportCsv() {
        if (lastLoadedRows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No orders to export for this filter.");
            return;
        }
        try {
            java.nio.file.Path file = CsvExport.exportOrders(lastLoadedRows);
            JOptionPane.showMessageDialog(this, "Exported to:\n" + file, "Export complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final class OrdersTableModel extends AbstractTableModel {
        private final String[] cols = {"Order #", "Time", "Type", "Items", "Total", "Status", "Staff", ""};
        private List<OrderDao.OrderRow> rows = List.of();

        void setOrders(List<OrderDao.OrderRow> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        Order orderAt(int row) {
            return rows.get(row).order();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int col) { return cols[col]; }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col == COL_ACTION;
        }

        /** "20260918-007" -> "007". Order numbers are always DATE-SEQ (OrderDao#saveOrder),
         *  so the sequence is everything after the last hyphen; anything that doesn't
         *  match that shape (there is no such case today, but this is display code, not
         *  a parser the rest of the app depends on) is shown unchanged rather than
         *  guessing at a substring. */
        private static String displayOrderNumber(String orderNumber) {
            int dash = orderNumber.lastIndexOf('-');
            return dash < 0 || dash == orderNumber.length() - 1 ? orderNumber : orderNumber.substring(dash + 1);
        }

        @Override
        public Object getValueAt(int row, int col) {
            OrderDao.OrderRow r = rows.get(row);
            Order o = r.order();
            return switch (col) {
                // Only the day's own sequence number, not the yyyyMMdd- prefix — the
                // dashboard is always looking at a chosen date range already (shown by
                // the date picker itself), so repeating that date on every single row
                // added nothing a cashier actually reads at a glance. The real, full
                // order number (needed for a receipt reprint, a refund conversation,
                // anything that leaves this screen) is untouched everywhere else — the
                // detail view, receipts, CSV export, the database — this is display-only.
                case 0 -> displayOrderNumber(o.orderNumber());
                case 1 -> o.createdAt().format(TS);
                case 2 -> o.type().label();
                case 3 -> r.itemCount();
                case 4 -> o.totals().total().format();
                // A cancelled order shows as Cancelled regardless of its payment state —
                // that overrides Paid/Unpaid here rather than needing its own column, but
                // the fulfilment status is not otherwise surfaced anywhere in this table.
                case COL_STATUS -> o.fulfilmentStatus().isCancelled() ? o.fulfilmentStatus() : o.paymentStatus();
                case 6 -> o.staffName();
                case COL_ACTION -> OrderDao.canEdit(o) ? "Edit" : "Add";
                default -> "";
            };
        }
    }
}
