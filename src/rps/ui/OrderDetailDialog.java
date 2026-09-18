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
        headerRow.add(UiFactory.statusPill(order.status()), BorderLayout.EAST);
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
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            String fullName = l.quantity() + "x " + l.displayName();
            JLabel nameLabel = UiFactory.label(truncateName(fullName));
            if (fullName.length() > 44) nameLabel.setToolTipText(fullName);
            row.add(nameLabel, BorderLayout.WEST);
            row.add(UiFactory.label(l.lineTotal().format()), BorderLayout.EAST);
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
        close.setAlignmentX(Component.CENTER_ALIGNMENT);
        close.addActionListener(e -> dispose());
        content.add(close);

        // Width-tracks the scroll pane viewport (see ScrollableColumn) so no child —
        // an unusually long line-item name, delivery address, or note — can force this
        // panel, and therefore the whole dialog, wider than its fixed preferred size.
        ScrollableColumn outer = new ScrollableColumn(new BorderLayout());
        outer.setBackground(Theme.BACKGROUND);
        outer.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_MD, Theme.SPACE_MD, Theme.SPACE_MD, Theme.SPACE_MD));
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

    private static JLabel line(String text) {
        JLabel l = UiFactory.muted("<html><div style='width:" + CONTENT_WIDTH_PX + "px'>"
            + escapeHtml(text) + "</div></html>");
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Mirrors PosPanel's tileDisplayName — a long deal name (which carries its full
     *  contents in the name itself, e.g. "Heavy Deal: 2 Chicken Burger + ...") is cut
     *  to something that fits one row, with the full text as a tooltip. Keeping this a
     *  single line (rather than wrapping like line()) is deliberate: a line-item row
     *  is a BorderLayout WEST/EAST pair with the price on the right, and a wrapped
     *  multi-line WEST label would run underneath — or push out — that price. */
    private static String truncateName(String name) {
        if (name.length() <= 44) return name;
        int cut = name.lastIndexOf(' ', 41);
        if (cut < 15) cut = 41;
        return name.substring(0, cut).stripTrailing() + "…";
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
