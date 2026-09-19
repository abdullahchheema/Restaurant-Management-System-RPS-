package rps.ui;

import rps.model.Order;
import rps.model.OrderLine;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;

import javax.swing.*;
import java.awt.*;
import java.time.format.DateTimeFormatter;

/** Read-only view of a saved order: lines, customer info, and totals — the same data
 *  loadOrderForPrint already returns for reprinting, just rendered instead of printed. */
final class OrderDetailDialog extends JDialog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", java.util.Locale.ENGLISH);

    OrderDetailDialog(Component parent, Order order) {
        super(SwingUtilities.getWindowAncestor(parent), "Order " + order.orderNumber(), ModalityType.APPLICATION_MODAL);

        getContentPane().setBackground(Theme.BACKGROUND);

        rps.ui.theme.Card content = new rps.ui.theme.Card();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerRow.add(UiFactory.title(order.orderNumber()), BorderLayout.WEST);
        headerRow.add(statusPills(order), BorderLayout.EAST);
        content.add(headerRow);
        content.add(Box.createVerticalStrut(4));
        content.add(line(order.type().label() + "  ·  " + order.createdAt().format(TS) + "  ·  Staff: " + order.staffName()));

        if (order.tableNumber() != null && !order.tableNumber().isBlank()) {
            content.add(Box.createVerticalStrut(4));
            content.add(line("Table: " + order.tableNumber()));
        }
        if (order.customerPhone() != null) {
            content.add(Box.createVerticalStrut(4));
            content.add(line("Phone: " + order.customerPhone()));
        }
        if (order.isDelivery() && order.deliveryAddress() != null) {
            content.add(line("Address: " + order.deliveryAddress()));
        }
        if (order.notes() != null && !order.notes().isBlank()) {
            content.add(line("Notes: " + order.notes()));
        }

        content.add(Box.createVerticalStrut(14));
        content.add(divider());
        content.add(Box.createVerticalStrut(10));

        for (OrderLine l : order.lines()) {
            // Name in CENTER, price in EAST. BorderLayout gives EAST its preferred width
            // and hands CENTER whatever is left, and a JLabel narrower than its text
            // ellipsises itself — so the name can never run into the price. It previously
            // sat in WEST and was cut to a fixed 44 CHARACTERS, but the font is
            // proportional, so a wide name still measured past the price and overlapped it
            // ("...+ 2 ReRs. 1,450.00"). Letting the layout measure in pixels removes the
            // guesswork entirely.
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            String fullName = l.quantity() + "x " + l.displayName();
            JLabel nameLabel = UiFactory.label(fullName);
            nameLabel.setToolTipText(fullName);
            JLabel priceLabel = UiFactory.label(l.lineTotal().format());
            // Without this the row's preferred width is name+price at full length, which
            // would widen the whole scrollable column rather than ellipsising.
            nameLabel.setMinimumSize(new Dimension(0, nameLabel.getPreferredSize().height));
            row.add(nameLabel, BorderLayout.CENTER);
            row.add(priceLabel, BorderLayout.EAST);
            content.add(row);
            if (l.hasOption()) {
                content.add(UiFactory.muted("   " + l.optionGroupName() + ": " + l.optionValueName()));
            }
            if (l.notes() != null && !l.notes().isBlank()) {
                content.add(UiFactory.muted("   note: " + l.notes()));
            }
        }

        content.add(Box.createVerticalStrut(14));
        content.add(divider());
        content.add(Box.createVerticalStrut(10));

        content.add(totalsRow("Subtotal", order.totals().subtotal().format(), false));
        if (!order.totals().discountTotal().isZero()) {
            content.add(totalsRow("Discount", "-" + order.totals().discountTotal().format(), false));
        }
        if (!order.totals().deliveryFee().isZero()) {
            content.add(totalsRow("Delivery fee", "+" + order.totals().deliveryFee().format(), false));
        }
        content.add(totalsRow("Total", order.totals().total().format(), true));
        if (order.totals().hasCashTendered()) {
            content.add(totalsRow("Cash", order.totals().cashTendered().format(), false));
            content.add(totalsRow("Change", order.totals().changeDue().format(), false));
        }
        content.add(totalsRow("Paid", order.totals().amountPaid().format(), false));
        if (!order.totals().isFullyPaid()) {
            content.add(totalsRow("Balance due", order.totals().balanceDue().format(), false));
        }

        content.add(Box.createVerticalStrut(16));
        JButton close = UiFactory.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        // Centred by wrapping, not by setAlignmentX on the button itself. BoxLayout
        // resolves ONE alignment point across all its children, so a single centre-aligned
        // child among left-aligned siblings gets allocated less than its own minimum
        // width — measured at 76px against a 89px minimum, which is why this rendered as
        // "C..." instead of "Close". A centred FlowLayout row keeps every direct child of
        // the BoxLayout left-aligned, so nothing is squeezed.
        JPanel closeRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        closeRow.setOpaque(false);
        closeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        closeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, close.getPreferredSize().height));
        closeRow.add(close);
        content.add(closeRow);

        // Width-tracks the scroll pane viewport (see ScrollableColumn) so no child —
        // an unusually long line-item name, delivery address, or note — can force this
        // panel, and therefore the whole dialog, wider than its fixed preferred size.
        ScrollableColumn outer = new ScrollableColumn(new BorderLayout());
        outer.setBackground(Theme.BACKGROUND);
        outer.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_MD, Theme.SPACE_MD, Theme.SPACE_MD, Theme.SPACE_MD));
        TouchScroll.install(outer);
        outer.add(content, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(outer,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.BACKGROUND);

        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(420, 520));
        pack();
        setLocationRelativeTo(parent);
    }

    /** Content width available inside the dialog (420 preferred, minus the outer +
     *  Card insets and the scrollbar), used to wrap free-text fields — delivery
     *  address and order notes have no length limit, so without wrapping a long one
     *  would force this whole scrollable panel wider, exactly the bug that pushed
     *  every right-anchored total and the status pill off past the visible edge. */
    private static final int CONTENT_WIDTH_PX = 320;

    /** One pill: the payment state, unless the order is cancelled — in which case that
     *  overrides it, same rule as the Dashboard's single Status column. Fulfilment is
     *  otherwise not shown here; it isn't something the counter reads day to day. */
    private static JComponent statusPills(Order order) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        row.setOpaque(false);
        row.add(order.fulfilmentStatus().isCancelled()
            ? UiFactory.fulfilmentPill(order.fulfilmentStatus())
            : UiFactory.statusPill(order.paymentStatus()));
        return row;
    }

    private static JLabel line(String text) {
        JLabel l = UiFactory.muted("<html><div style='width:" + CONTENT_WIDTH_PX + "px'>"
            + escapeHtml(text) + "</div></html>");
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JComponent divider() {
        JPanel d = new JPanel();
        d.setBackground(Theme.BORDER);
        d.setPreferredSize(new Dimension(1, 1));
        d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        d.setAlignmentX(Component.LEFT_ALIGNMENT);
        return d;
    }

    private static JComponent totalsRow(String label, String value, boolean emphasized) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        row.add(emphasized ? UiFactory.heading(label) : UiFactory.label(label), BorderLayout.WEST);
        JLabel v = emphasized ? UiFactory.heading(value) : UiFactory.label(value);
        if (emphasized) v.setForeground(Theme.PRIMARY);
        row.add(v, BorderLayout.EAST);
        return row;
    }
}
