package rps.ui;

import rps.app.Session;
import rps.db.DatabaseException;
import rps.db.Db;
import rps.db.OrderDao;
import rps.model.Order;
import rps.ui.icon.LineIcon;
import rps.ui.theme.EmptyState;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Pay Later ledger: orders the customer left without settling, by agreement.
 *
 * <p>Deliberately not a filter on the Dashboard. A debt is a different kind of thing from
 * a live order — it is not waiting on the kitchen, it is not going to be settled at the
 * counter in the next five minutes, and it must not age out of view with the date range
 * the way an ordinary order does. So these rows leave the Dashboard entirely
 * (OrderDao#loadOrders excludes them) and live here instead, unbounded by date, oldest
 * debt first.
 *
 * <p>Settled loans are kept rather than vanishing the moment they are paid off, because
 * the useful question is often "has this customer paid us back before", not just "who owes
 * us right now" — hence the view filter rather than an implicit rule.
 */
public final class LoanPanel extends JPanel {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", java.util.Locale.ENGLISH);
    private static final int POLL_MS = 6000;

    private static final int COL_BALANCE = 6;
    private static final int COL_ACTION = 7;

    private final Session session;
    private final OrderDao orderDao;

    private final LoansTableModel model = new LoansTableModel();
    private final JTable table = new JTable(model);

    private final JLabel owedValue = statCardValueLabel();
    private final JLabel countValue = statCardValueLabel();
    private final JLabel receivedValue = statCardValueLabel();

    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerHolder = new JPanel(centerCards);
    private final EmptyState emptyState =
        new EmptyState(LineIcon.PERSON, "Nothing on account", "Orders moved to Pay Later from the Dashboard appear here.");
    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    private final Timer pollTimer;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private String lastFingerprint = "";

    public LoanPanel(Db db, Session session) {
        this.session = session;
        this.orderDao = new OrderDao(db);

        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG));

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        pollTimer = new Timer(POLL_MS, e -> refresh(false));
        pollTimer.setRepeats(true);

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

    private static JLabel statCardValueLabel() {
        JLabel l = new JLabel("—");
        l.setFont(Theme.FONT_BRAND.deriveFont(Font.BOLD, 26f));
        l.setForeground(Theme.PRIMARY);
        return l;
    }

    private JComponent buildTop() {
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel statsRow = new JPanel(new GridLayout(1, 3, 16, 0));
        statsRow.setOpaque(false);
        statsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Owed and Received are the two halves of the same pot: collecting on a debt moves
        // money from the left card to the right one, so between them they always account
        // for every rupee given on credit.
        statsRow.add(statCard("Still Owed", owedValue));
        statsRow.add(statCard("Customers Owing", countValue));
        statsRow.add(statCard("Received Back", receivedValue));
        top.add(statsRow);
        top.add(Box.createVerticalStrut(Theme.SPACE_SM));

        // No status filter here any more: an order collected in full settles itself and
        // moves back to the Dashboard, so everything this screen can show is by definition
        // still owed. A "Settled" view would always be empty.
        JLabel hint = UiFactory.muted(
            "Debts still to collect. Collecting one in full moves it back to the Dashboard as a settled order.");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(hint);

        return top;
    }

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

    private JComponent buildCenter() {
        table.setFont(Theme.FONT_BODY);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(Theme.PRIMARY_TINT);
        table.setSelectionForeground(Theme.TEXT);
        table.setDefaultRenderer(Object.class, UiFactory.centeredCellRenderer());
        table.getColumnModel().getColumn(COL_BALANCE).setCellRenderer(balanceCellRenderer());

        var actionCol = table.getColumnModel().getColumn(COL_ACTION);
        actionCol.setCellRenderer((t, value, isSelected, hasFocus, row, column) ->
            compactTableButton(String.valueOf(value)));
        actionCol.setCellEditor(new CollectButtonEditor());
        actionCol.setResizable(false);
        actionCol.setMinWidth(88);
        actionCol.setMaxWidth(88);

        // Wired through TouchScroll, not a plain click listener: the till is touchscreen,
        // and a swipe to scroll this table very likely starts on a row — a movement past
        // the drag threshold has to pan the table instead of opening whatever row it
        // started on.
        TouchScroll.install(table, (MouseEvent e) -> {
            if (!SwingUtilities.isLeftMouseButton(e)) return;
            int row = table.rowAtPoint(e.getPoint());
            int col = table.columnAtPoint(e.getPoint());
            if (row < 0 || col == COL_ACTION) return;
            showOrderDetail(model.orderAt(row).id());
        });

        JPopupMenu menu = new JPopupMenu();
        JMenuItem detail = new JMenuItem("View Details");
        detail.addActionListener(e -> withSelectedRow(o -> showOrderDetail(o.id())));
        JMenuItem back = new JMenuItem("Return to Dashboard");
        back.addActionListener(e -> withSelectedRow(this::returnToDashboard));
        menu.add(detail);
        menu.add(back);
        table.addMouseListener(new MouseAdapter() {
            private void selectRowUnderPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) table.setRowSelectionInterval(row, row);
                }
            }
            @Override public void mousePressed(MouseEvent e) { selectRowUnderPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { selectRowUnderPopup(e); }
        });
        table.setComponentPopupMenu(menu);

        JScrollPane scroll = new JScrollPane(table,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(Theme.SURFACE);

        centerHolder.setOpaque(false);
        centerHolder.add(scroll, CARD_TABLE);
        centerHolder.add(emptyState, CARD_EMPTY);
        return centerHolder;
    }

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

    /** The balance is the whole point of this screen, so it is the one column that is
     *  coloured — and every row here is by definition still owed. */
    private static TableCellRenderer balanceCellRenderer() {
        return (t, value, isSelected, hasFocus, row, column) -> {
            JLabel l = new JLabel(String.valueOf(value), SwingConstants.CENTER);
            l.setFont(Theme.FONT_BODY_BOLD);
            l.setOpaque(true);
            l.setBackground(isSelected ? t.getSelectionBackground() : t.getBackground());
            l.setForeground(Theme.STATUS_PENDING);
            return l;
        };
    }

    private final class CollectButtonEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button = compactTableButton("Collect");
        private Order current;

        CollectButtonEditor() {
            button.addActionListener((ActionEvent e) -> {
                fireEditingStopped();
                if (current != null) collectFrom(current);
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable t, Object value, boolean isSelected, int row, int column) {
            current = model.orderAt(row);
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return "Collect";
        }
    }

    private interface OrderAction {
        void accept(Order o) throws DatabaseException;
    }

    private void withSelectedRow(OrderAction action) {
        int row = table.getSelectedRow();
        if (row < 0) return;
        try {
            action.accept(model.orderAt(row));
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Takes money against a debt. Reuses OrderDao#recordPayment unchanged — a loan is
     *  still an ordinary unpaid order underneath, which is exactly why the loan flag was
     *  kept separate from payment_status rather than replacing it. */
    private void collectFrom(Order order) {
        Money balanceDue = order.totals().balanceDue();
        JTextField amountField = UiFactory.textField(10);
        JButton fullBtn = UiFactory.secondaryButton("Paid in Full");
        fullBtn.addActionListener(e -> {
            amountField.setText(balanceDue.asBigDecimal().toPlainString());
            amountField.requestFocusInWindow();
        });
        JPanel amountRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        amountRow.setOpaque(false);
        amountRow.add(amountField);
        amountRow.add(fullBtn);

        String who = order.customerName() == null || order.customerName().isBlank()
            ? "Order " + order.orderNumber() : order.customerName();
        int choice = JOptionPane.showConfirmDialog(this,
            new Object[]{who + " owes " + balanceDue.format(), "Amount received:", amountRow},
            "Collect Payment", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        Money amount = Validators.parsePrice(amountField.getText());
        if (amount == null || !amount.isPositive()) {
            JOptionPane.showMessageDialog(this, "Enter a valid amount greater than zero.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (!orderDao.recordPayment(order.id(), amount, session.staffId())) {
                JOptionPane.showMessageDialog(this,
                    "This order was already updated by another terminal. Refreshing.",
                    "Out of date", JOptionPane.WARNING_MESSAGE);
            }
            // A debt cleared in full settles itself and leaves this screen, so say where it
            // went — otherwise the row simply vanishes and the cashier is left wondering
            // whether the payment actually registered.
            Order after = orderDao.loadOrderForPrint(order.id());
            if (after != null && !after.isOnLoanLedger()) {
                JOptionPane.showMessageDialog(this,
                    "Paid in full. Order " + order.orderNumber()
                        + " has moved back to the Dashboard as a settled order.",
                    "Debt cleared", JOptionPane.INFORMATION_MESSAGE);
            }
            refresh(true);
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** For an order put on account by mistake — it goes back to the Dashboard exactly as
     *  it was, since moving it here never changed its payment or fulfilment state. */
    private void returnToDashboard(Order order) throws DatabaseException {
        int confirm = JOptionPane.showConfirmDialog(this,
            "Move order " + order.orderNumber() + " back to the Dashboard?\n\n"
                + "It will stop counting as revenue and show as outstanding again.",
            "Return to Dashboard", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        orderDao.returnFromLoan(order.id());
        refresh(true);
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

    private void refresh(boolean force) {
        if (!inFlight.compareAndSet(false, true)) return;

        SwingWorker<Object[], Void> worker = new SwingWorker<>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                // Belt-and-braces: settle anything already fully paid before reading the
                // ledger, so a row stuck from before applyPayment's own settle step existed
                // (or any other bug that reaches PAID without going through it) self-heals
                // here instead of sitting forever with nothing left to collect.
                orderDao.reconcileLoanLedger();
                String fp = orderDao.loanFingerprint();
                if (!force && fp.equals(lastFingerprint)) return null;
                lastFingerprint = fp;
                return new Object[]{orderDao.loadLoanOrders(), orderDao.loanSummary()};
            }

            @Override
            protected void done() {
                inFlight.set(false);
                try {
                    Object[] result = get();
                    if (result == null) return;
                    @SuppressWarnings("unchecked")
                    List<OrderDao.OrderRow> rows = (List<OrderDao.OrderRow>) result[0];
                    OrderDao.LoanSummary summary = (OrderDao.LoanSummary) result[1];
                    owedValue.setText(summary.outstandingAmount().format());
                    countValue.setText(String.valueOf(summary.outstandingCount()));
                    receivedValue.setText(summary.receivedTotal().format());
                    model.setRows(rows);
                    centerCards.show(centerHolder, rows.isEmpty() ? CARD_EMPTY : CARD_TABLE);
                } catch (Exception ex) {
                    // Same reasoning as DashboardPanel: a failed poll must not throw a
                    // dialog every few seconds; the header's connection dot is what
                    // actually reports an outage.
                    System.err.println("Pay Later refresh failed: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    private static final class LoansTableModel extends AbstractTableModel {
        private final String[] cols = {"Order #", "Given On", "Customer", "Phone", "Total", "Paid", "Still Owed", ""};
        private List<OrderDao.OrderRow> rows = List.of();

        void setRows(List<OrderDao.OrderRow> rows) {
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

        @Override
        public Object getValueAt(int row, int col) {
            Order o = orderAt(row);
            return switch (col) {
                // The full DATE-SEQ number, unlike the Dashboard's day-sequence-only
                // column: this list spans every date at once, so "007" alone would be
                // ambiguous between as many orders as there are days on the ledger.
                case 0 -> o.orderNumber();
                case 1 -> o.loanAt() == null ? "" : o.loanAt().format(DATE);
                case 2 -> o.customerName() == null || o.customerName().isBlank() ? "—" : o.customerName();
                case 3 -> o.customerPhone() == null || o.customerPhone().isBlank() ? "—" : o.customerPhone();
                case 4 -> o.totals().total().format();
                case 5 -> o.totals().amountPaid().format();
                case COL_BALANCE -> o.totals().balanceDue().format();
                case COL_ACTION -> "Collect";
                default -> "";
            };
        }
    }
}
