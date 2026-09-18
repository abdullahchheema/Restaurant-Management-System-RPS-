package rps.ui;

import rps.app.Session;
import rps.db.DatabaseException;
import rps.db.Db;
import rps.db.DeliveryDao;
import rps.model.DeliveryOrderSummary;
import rps.model.DeliveryRun;
import rps.model.DeliveryRunStatus;
import rps.model.OrderStatus;
import rps.ui.theme.EmptyState;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.Money;
import rps.util.Validators;

import javax.swing.*;
import java.awt.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Riders and delivery runs: assign a batch of orders (any order type — a rider can just
 * as well carry out a Takeaway order) to a rider, watch what's
 * currently out, and settle a run when the rider is back — see DeliveryDao#completeRun
 * for the settlement rule (the whole run's outstanding balance must be covered before
 * any of its orders move to Payment Received).
 */
public final class DeliveryPanel extends JPanel {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd MMM, HH:mm", java.util.Locale.ENGLISH);
    private static final int POLL_MS = 4000;

    private final Session session;
    private final DeliveryDao deliveryDao;

    private final JPanel runList = new JPanel();
    private final JLabel refreshingDot = refreshingDotStyled();
    private final DateRangePicker dateRangePicker =
        new DateRangePicker(rps.db.OrderDao.businessDate(ZonedDateTime.now()), rps.db.OrderDao.businessDate(ZonedDateTime.now()));

    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerHolder = new JPanel(centerCards);
    private final EmptyState emptyState = new EmptyState(rps.ui.icon.LineIcon.DELIVERY,
        "No delivery runs", "Start one to assign orders to a rider.");
    private static final String CARD_LIST = "list";
    private static final String CARD_EMPTY = "empty";

    private final Timer pollTimer;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private String lastSignature = "";

    public DeliveryPanel(Db db, Session session) {
        this.session = session;
        this.deliveryDao = new DeliveryDao(db);

        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG));

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        pollTimer = new Timer(POLL_MS, e -> refresh());
        pollTimer.setRepeats(true);

        dateRangePicker.onApply((f, t) -> refresh());

        refresh();
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

    private static JLabel refreshingDotStyled() {
        JLabel l = new JLabel("● Refreshing");
        l.setFont(Theme.FONT_SMALL);
        l.setForeground(Theme.ACCENT_DEEP);
        l.setVisible(false);
        return l;
    }

    private JComponent buildTop() {
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerRow.add(UiFactory.title("Deliveries"), BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton manageRiders = UiFactory.secondaryButton("Manage Riders");
        manageRiders.addActionListener(e -> new RiderManagerDialog(this, deliveryDao, this::refresh).setVisible(true));
        JButton newRun = UiFactory.primaryButton("Start Delivery Run");
        newRun.addActionListener(e -> {
            DeliveryRun created = StartDeliveryRunDialog.show(this, deliveryDao, session.staffId(), session.staffName());
            if (created != null) refresh();
        });
        actions.add(manageRiders);
        actions.add(newRun);
        headerRow.add(actions, BorderLayout.EAST);
        top.add(headerRow);

        top.add(Box.createVerticalStrut(Theme.SPACE_LG));

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        filterRow.setOpaque(false);
        filterRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel dateWrap = new JPanel(new BorderLayout(0, 6));
        dateWrap.setOpaque(false);
        dateWrap.add(UiFactory.label("History date range"), BorderLayout.NORTH);
        dateWrap.add(dateRangePicker, BorderLayout.CENTER);
        filterRow.add(dateWrap);
        JPanel refreshWrap = new JPanel(new BorderLayout());
        refreshWrap.setOpaque(false);
        refreshWrap.add(Box.createVerticalStrut(20), BorderLayout.NORTH);
        refreshWrap.add(refreshingDot, BorderLayout.CENTER);
        filterRow.add(refreshWrap);
        top.add(filterRow);

        return top;
    }

    private JComponent buildCenter() {
        runList.setOpaque(false);
        runList.setLayout(new BoxLayout(runList, BoxLayout.Y_AXIS));

        ScrollableColumn wrap = new ScrollableColumn(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(runList, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.BACKGROUND);

        centerHolder.setOpaque(false);
        centerHolder.add(scroll, CARD_LIST);
        centerHolder.add(emptyState, CARD_EMPTY);
        return centerHolder;
    }

    private void refresh() {
        if (!inFlight.compareAndSet(false, true)) return;

        Timer showRefreshing = new Timer(250, e -> refreshingDot.setVisible(true));
        showRefreshing.setRepeats(false);
        showRefreshing.start();

        var from = dateRangePicker.from();
        var to = dateRangePicker.to();

        SwingWorker<Object[], Void> worker = new SwingWorker<>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                List<DeliveryRun> open = deliveryDao.listOpenRuns();
                List<DeliveryRun> closed = deliveryDao.listClosedRuns(from, to);
                return new Object[]{open, closed};
            }

            @Override
            protected void done() {
                inFlight.set(false);
                showRefreshing.stop();
                refreshingDot.setVisible(false);
                try {
                    Object[] result = get();
                    @SuppressWarnings("unchecked")
                    List<DeliveryRun> open = (List<DeliveryRun>) result[0];
                    @SuppressWarnings("unchecked")
                    List<DeliveryRun> closed = (List<DeliveryRun>) result[1];
                    // Only rebuild when something actually changed. render() tears the
                    // whole card list down and recreates it, which resets the scroll
                    // position — on a 4s timer that made the panel jump back to the top
                    // every few seconds while anyone was reading it further down.
                    String signature = signatureOf(open, closed);
                    if (signature.equals(lastSignature)) return;
                    lastSignature = signature;
                    render(open, closed);
                } catch (Exception ex) {
                    System.err.println("Deliveries refresh failed: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    /** Everything this screen actually renders, flattened — including each assigned
     *  order's status and balance, so a payment recorded on one of those orders from
     *  the Dashboard still repaints here even though delivery_run itself didn't change
     *  (which is exactly what a fingerprint over delivery_run alone would have missed). */
    private static String signatureOf(List<DeliveryRun> open, List<DeliveryRun> closed) {
        StringBuilder sb = new StringBuilder();
        for (List<DeliveryRun> group : List.of(open, closed)) {
            for (DeliveryRun run : group) {
                sb.append(run.id()).append(':').append(run.status()).append(':')
                  .append(run.riderName()).append(':').append(run.collectedAmount()).append('|');
                for (DeliveryOrderSummary o : run.orders()) {
                    sb.append(o.orderId()).append(',').append(o.status()).append(',')
                      .append(o.balanceDue()).append(';');
                }
                sb.append('\n');
            }
            sb.append("--\n");
        }
        return sb.toString();
    }

    private void render(List<DeliveryRun> open, List<DeliveryRun> closed) {
        runList.removeAll();
        if (open.isEmpty() && closed.isEmpty()) {
            centerCards.show(centerHolder, CARD_EMPTY);
            return;
        }
        if (!open.isEmpty()) {
            runList.add(sectionHeading("Out for Delivery (" + open.size() + ")"));
            for (DeliveryRun run : open) {
                runList.add(runCard(run));
                runList.add(Box.createVerticalStrut(12));
            }
        }
        if (!closed.isEmpty()) {
            runList.add(sectionHeading("History"));
            for (DeliveryRun run : closed) {
                runList.add(runCard(run));
                runList.add(Box.createVerticalStrut(12));
            }
        }
        runList.revalidate();
        runList.repaint();
        centerCards.show(centerHolder, CARD_LIST);
    }

    private static JComponent sectionHeading(String text) {
        JLabel l = UiFactory.heading(text);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(4, 2, 10, 0));
        return l;
    }

    private JComponent runCard(DeliveryRun run) {
        rps.ui.theme.Card card = new rps.ui.theme.Card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(UiFactory.labelBold(run.riderName()));
        String when = run.status() == DeliveryRunStatus.OPEN
            ? "Out since " + run.createdAt().format(TS)
            : run.createdAt().format(TS) + "  →  " + (run.closedAt() == null ? "" : run.closedAt().format(TS));
        left.add(UiFactory.muted(when + "  ·  Started by " + run.staffName()));
        headerRow.add(left, BorderLayout.WEST);
        headerRow.add(runStatusPill(run.status()), BorderLayout.EAST);
        card.add(headerRow);
        card.add(Box.createVerticalStrut(10));
        card.add(divider());
        card.add(Box.createVerticalStrut(10));

        for (DeliveryOrderSummary o : run.orders()) {
            card.add(orderRow(run, o));
        }

        card.add(Box.createVerticalStrut(10));
        card.add(divider());
        card.add(Box.createVerticalStrut(10));

        if (run.status() == DeliveryRunStatus.OPEN) {
            JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            footer.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Spell out that this covers only the unpaid orders — with a prepaid order
            // sitting in the same list, a bare "Expected" figure invited the reader to
            // check it against the sum of every line and conclude it was wrong.
            long toCollect = run.orders().stream().filter(o -> o.status().awaitsPayment()).count();
            JPanel expectedBox = new JPanel();
            expectedBox.setOpaque(false);
            expectedBox.setLayout(new BoxLayout(expectedBox, BoxLayout.Y_AXIS));
            // Value carried and cash to collect are different numbers and both matter:
            // a run of entirely prepaid orders carries real value but collects nothing,
            // so showing only one of them tells half the story.
            expectedBox.add(UiFactory.muted("Delivery value: " + run.totalValue().format()));
            expectedBox.add(UiFactory.labelBold("Rider collects: " + run.expectedAmount().format()));
            expectedBox.add(UiFactory.muted(toCollect == run.orders().size()
                ? "from all " + toCollect + " order" + (toCollect == 1 ? "" : "s")
                : "from " + toCollect + " of " + run.orders().size() + " orders — the rest need no payment"));
            footer.add(expectedBox, BorderLayout.WEST);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            JButton cancelBtn = UiFactory.secondaryButton("Cancel Run");
            cancelBtn.addActionListener(e -> cancelRun(run));
            JButton completeBtn = UiFactory.primaryButton("Complete Run");
            completeBtn.addActionListener(e -> completeRun(run));
            buttons.add(cancelBtn);
            buttons.add(completeBtn);
            footer.add(buttons, BorderLayout.EAST);
            card.add(footer);
        } else if (run.status() == DeliveryRunStatus.COMPLETED) {
            // Both figures, always: "Collected: Rs 0.00" on its own reads as a failed
            // run, when in fact the rider may have delivered thousands of rupees of
            // already-paid orders and correctly brought back no cash.
            card.add(UiFactory.labelBold("Delivery value: " + run.totalValue().format()));
            Money collected = run.collectedAmount() == null ? Money.ZERO : run.collectedAmount();
            JLabel collectedLabel = UiFactory.muted("Collected on delivery: " + collected.format()
                + (collected.isPositive() || !run.expectedAmount().isPositive()
                    ? "" : "  ·  nothing was handed over"));
            card.add(collectedLabel);
            if (!collected.isPositive() && !run.expectedAmount().isPositive()) {
                card.add(UiFactory.muted("All orders on this run were already paid."));
            }
            // A run closed short keeps its unpaid orders attached (see
            // DeliveryDao#closeRunWithUnpaid) so the history stays intact — say so here,
            // otherwise a Completed run silently hiding money still owed looks settled.
            if (run.expectedAmount().isPositive()) {
                JLabel owed = UiFactory.muted("Still owed: " + run.expectedAmount().format()
                    + " — collect from the Dashboard");
                owed.setForeground(Theme.STATUS_PENDING);
                card.add(owed);
            }
        } else {
            card.add(UiFactory.muted("Cancelled — orders released back to unassigned."));
        }

        return card;
    }

    private JComponent orderRow(DeliveryRun run, DeliveryOrderSummary o) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        String who = o.customerName() == null || o.customerName().isBlank() ? "" : "  ·  " + o.customerName();
        text.add(UiFactory.label(o.orderNumber() + who));
        String subtitle = o.type().label();
        if (o.deliveryAddress() != null && !o.deliveryAddress().isBlank()) {
            String addr = o.deliveryAddress();
            subtitle += "  ·  " + (addr.length() > 60 ? addr.substring(0, 60) + "…" : addr);
        }
        text.add(UiFactory.muted(subtitle));
        if (o.itemsSummary() != null && !o.itemsSummary().isBlank()) {
            String items = o.itemsSummary();
            text.add(UiFactory.muted(items.length() > 70 ? items.substring(0, 70) + "…" : items));
        }
        row.add(text, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        // An order can be cancelled from the Dashboard while it's still sitting on an
        // open run. It's correctly excluded from the run's expected total, but it must
        // not keep advertising a balance the rider is never going to collect — showing
        // "Due Rs 700" next to a cancelled order reads as money still owed.
        //
        // Only the "Due" figure is money the rider has to bring back. An order paid at
        // ordering time previously rendered as "Paid Rs 1,050.00" — the same slot, same
        // weight as a Due amount — which read as though that cash was also in play for
        // this delivery. The amount is dropped entirely for a settled order (it's on the
        // receipt and the order detail already); what the rider needs to know here is
        // simply that there is nothing to collect.
        String amountText;
        Color amountColor;
        if (o.status() == OrderStatus.CANCELLED) {
            amountText = "Cancelled";
            amountColor = Theme.STATUS_CANCELLED;
        } else if (o.status() == OrderStatus.PAYMENT_RECEIVED) {
            amountText = "Already paid · collect nothing";
            amountColor = Theme.STATUS_COMPLETED;
        } else {
            amountText = "Collect " + o.balanceDue().format();
            amountColor = Theme.STATUS_PENDING;
        }
        JLabel amount = UiFactory.label(amountText);
        amount.setForeground(amountColor);
        right.add(amount);
        if (run.status() == DeliveryRunStatus.OPEN) {
            // Per-order collection: when the rider comes back with less than the full
            // expected amount, the cashier settles exactly the orders that were paid
            // rather than the system guessing how to spread a lump sum across them.
            if (o.status().awaitsPayment()) {
                JButton collect = UiFactory.secondaryButton("Collect");
                collect.addActionListener(e -> collectForOrder(run, o));
                right.add(collect);
            }
            JButton remove = UiFactory.secondaryButton("Remove");
            remove.addActionListener(e -> removeFromRun(run, o));
            right.add(remove);
        }
        row.add(right, BorderLayout.EAST);
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

    private static JLabel runStatusPill(DeliveryRunStatus status) {
        Color color = switch (status) {
            case OPEN -> Theme.STATUS_PENDING;
            case COMPLETED -> Theme.STATUS_COMPLETED;
            case CANCELLED -> Theme.STATUS_CANCELLED;
        };
        JLabel l = new JLabel(status.label());
        l.setOpaque(true);
        l.setForeground(color);
        l.setBackground(switch (status) {
            case OPEN -> Theme.STATUS_PENDING_TINT;
            case COMPLETED -> Theme.STATUS_COMPLETED_TINT;
            case CANCELLED -> Theme.STATUS_CANCELLED_TINT;
        });
        l.setFont(Theme.FONT_SMALL_BOLD);
        l.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(color, 1, true),
            BorderFactory.createEmptyBorder(3, 11, 3, 11)));
        return l;
    }

    private void removeFromRun(DeliveryRun run, DeliveryOrderSummary o) {
        try {
            boolean ok = deliveryDao.removeOrderFromRun(run.id(), o.orderId());
            if (!ok) {
                JOptionPane.showMessageDialog(this, "This run has already changed. Refreshing.",
                    "Out of date", JOptionPane.WARNING_MESSAGE);
            }
            refresh();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cancelRun(DeliveryRun run) {
        int confirm = JOptionPane.showConfirmDialog(this,
            "Cancel this run and release all its orders back to unassigned?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            boolean ok = deliveryDao.cancelRun(run.id());
            if (!ok) {
                JOptionPane.showMessageDialog(this, "This run has already changed. Refreshing.",
                    "Out of date", JOptionPane.WARNING_MESSAGE);
            }
            refresh();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Settles one order on an open run. Pre-fills the full balance, since collecting
     *  it in full is by far the common case, but allows less — a customer who paid part
     *  of their bill at the door leaves the order Partially Paid rather than forcing an
     *  all-or-nothing choice. */
    private void collectForOrder(DeliveryRun run, DeliveryOrderSummary o) {
        Object entered = JOptionPane.showInputDialog(this,
            "Order " + o.orderNumber() + "\nBalance due: " + o.balanceDue().format()
                + "\n\nAmount collected for this order:",
            o.balanceDue().asBigDecimal().toPlainString());
        if (entered == null || entered.toString().isBlank()) return;
        Money amount = Validators.parsePrice(entered.toString());
        if (amount == null || !amount.isPositive()) {
            JOptionPane.showMessageDialog(this, "Enter a valid amount greater than zero.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            boolean ok = deliveryDao.recordPaymentForOrder(o.orderId(), amount, session.staffId());
            if (!ok) {
                JOptionPane.showMessageDialog(this,
                    "That order was already settled or cancelled elsewhere. Refreshing.",
                    "Out of date", JOptionPane.WARNING_MESSAGE);
            }
            refresh();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void completeRun(DeliveryRun run) {
        // Nothing owed on any order (all prepaid, or the run is empty): asking "amount
        // collected from rider" and forcing a literal 0 to be typed is a pointless
        // gate — there is only one valid answer. Confirm and close it out.
        if (!run.expectedAmount().isPositive()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                run.orders().isEmpty()
                    ? "This run has no orders on it.\n\nClose it out?"
                    : "Every order on this run is already paid — the rider has no cash to hand over.\n\nComplete this run?",
                "Complete Run", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            try {
                deliveryDao.completeRun(run.id(), Money.ZERO, session.staffId());
                refresh();
            } catch (DatabaseException e) {
                JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
            return;
        }

        long toCollect = run.orders().stream().filter(o -> o.status().awaitsPayment()).count();
        long alreadyPaid = run.orders().size() - toCollect;
        String context = "Rider should be carrying " + run.expectedAmount().format()
            + " from " + toCollect + " unpaid order" + (toCollect == 1 ? "" : "s")
            + (alreadyPaid == 0 ? "" : "\n(" + alreadyPaid + " order" + (alreadyPaid == 1 ? " was" : "s were")
                + " already paid — no cash due for " + (alreadyPaid == 1 ? "it" : "them") + ")");
        String input = JOptionPane.showInputDialog(this,
            context + "\n\nAmount collected from rider:",
            "Complete Run", JOptionPane.PLAIN_MESSAGE);
        if (input == null || input.isBlank()) return;
        Money amount = Validators.parsePrice(input);
        if (amount == null || amount.isNegative()) {
            JOptionPane.showMessageDialog(this, "Enter a valid amount.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Short of the full expected amount. The system deliberately does NOT spread a
        // lump sum across the orders itself — which customer paid and which didn't is
        // not derivable from one number, and guessing wrong marks the wrong person as
        // settled. Hand it back to the cashier, who does know.
        if (amount.compareTo(run.expectedAmount()) < 0) {
            Money shortBy = run.expectedAmount().subtract(amount);
            Object[] options = {"Collect per order", "Close run, leave unpaid", "Back"};
            int choice = JOptionPane.showOptionDialog(this,
                amount.format() + " is " + shortBy.format() + " short of the expected "
                    + run.expectedAmount().format() + ".\n\n"
                    + "Use \"Collect\" on each order to record exactly which ones were paid,\n"
                    + "or close the run now and leave the rest owing.",
                "Not enough to settle everything", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (choice != 1) return;   // "Collect per order" and "Back" both just return

            // Closing this way credits NOTHING to any order — the figure only lands on the
            // delivery_run row, which no report reads. So a cashier who typed a real amount
            // here would hand over cash that the books never see, while the orders still
            // show the full balance owing. Say that plainly before it happens rather than
            // reporting a tidy-looking shortfall that doesn't match the Outstanding figure.
            if (amount.isPositive()) {
                int confirm = JOptionPane.showConfirmDialog(this,
                    "The " + amount.format() + " you entered will NOT be credited to any order.\n\n"
                        + "All " + toCollect + " unpaid order" + (toCollect == 1 ? "" : "s")
                        + " will stay fully owing (" + run.expectedAmount().format() + "),\n"
                        + "so that cash would not appear in the day's takings.\n\n"
                        + "To record money that was actually paid, go back and use \"Collect\"\n"
                        + "on each order that paid.\n\nClose the run anyway?",
                    "This money will not be recorded", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) return;
            }

            try {
                boolean ok = deliveryDao.closeRunWithUnpaid(run.id(), amount, session.staffId());
                if (!ok) {
                    JOptionPane.showMessageDialog(this, "This run has already changed. Refreshing.",
                        "Out of date", JOptionPane.WARNING_MESSAGE);
                } else {
                    // The full expected amount is what remains owing, not the shortfall:
                    // nothing was applied, so no order's balance moved.
                    JOptionPane.showMessageDialog(this,
                        "Run closed. " + run.expectedAmount().format() + " is still owed across "
                            + toCollect + " order" + (toCollect == 1 ? "" : "s")
                            + " — they stay unpaid and can be collected from the Dashboard.",
                        "Run closed", JOptionPane.INFORMATION_MESSAGE);
                }
                refresh();
            } catch (DatabaseException e) {
                JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
            return;
        }

        try {
            deliveryDao.completeRun(run.id(), amount, session.staffId());
            refresh();
        } catch (DatabaseException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
