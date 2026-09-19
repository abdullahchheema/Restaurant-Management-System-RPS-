package rps.print;

import rps.model.DiscountMode;
import rps.model.Order;
import rps.model.OrderLine;
import rps.model.OrderTotals;
import rps.util.AppSettings;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the kitchen ticket from an Order already re-read from the database after commit,
 * so the printed document and the stored record can never disagree. Width is a parameter
 * (columns), not hardcoded — 48 for an 80mm roll, 32 for 58mm — so a receipt printer of
 * either size lays out correctly.
 *
 * <p>There is deliberately no customer-facing receipt any more: this shop prints two
 * identical kitchen tickets per order (see ReceiptPrinter) and hands nothing to the
 * customer. The shop name is the one piece of branding this ticket still carries — just
 * the configured name (Settings), never the address/phone/footer the old customer receipt
 * used to show; there is no one outside the kitchen to read those on this document.
 */
public final class ReceiptRenderer {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", java.util.Locale.ENGLISH);

    private final int width;

    public ReceiptRenderer(int width) {
        this.width = width;
    }

    /** Kitchen ticket: the shop name, names/sizes/quantities/notes, order type, who took
     *  it, the full costing (item prices, subtotal, discount, delivery fee, total,
     *  cash/change or balance due) and delivery/customer detail — still no address, phone,
     *  or footer, since there is no customer-facing copy left for those to belong on. Two
     *  identical copies of this are what gets printed for every order.
     *
     *  <p>The order number is returned as the doc's headline rather than written into the
     *  body, so it prints oversized and bold at the very top; the body still carries the
     *  full DATE-SEQ number in its detail block below. The shop name is the first line of
     *  the body, printed at normal size directly beneath that oversized headline. */
    public ReceiptDoc kitchenTicket(Order order) {
        StringBuilder sb = new StringBuilder();
        center(sb, AppSettings.get().shopName().toUpperCase(Locale.ROOT));
        rule(sb);
        sb.append("Order: ").append(order.orderNumber()).append('\n');
        sb.append("Type:  ").append(order.type().label()).append('\n');
        sb.append("Time:  ").append(order.createdAt().format(TS)).append('\n');
        sb.append("Staff: ").append(order.staffName()).append('\n');
        if (order.tableNumber() != null && !order.tableNumber().isBlank()) {
            sb.append("Table: ").append(order.tableNumber()).append('\n');
        }
        if (order.customerName() != null && !order.customerName().isBlank()) {
            wrap(sb, "Customer: ", order.customerName());
        }
        if (order.isDelivery()) {
            wrap(sb, "Deliver to: ", order.deliveryAddress());
        }
        if (order.customerPhone() != null && !order.customerPhone().isBlank()) {
            sb.append("Phone: ").append(order.customerPhone()).append('\n');
        }
        rule(sb);
        itemLines(sb, order);
        rule(sb);
        totalsAndPayment(sb, order);
        rule(sb);
        if (order.notes() != null && !order.notes().isBlank()) {
            wrap(sb, "Notes: ", order.notes());
        }
        return new ReceiptDoc(ReceiptDoc.headlineFor(order.orderNumber()), sb.toString());
    }

    /** One line per order line: quantity + name on the left, that line's total on the
     *  right, with each line's cooking notes underneath it. */
    private void itemLines(StringBuilder sb, Order order) {
        for (OrderLine line : order.lines()) {
            String left = line.quantity() + "x " + line.displayName();
            String right = line.lineTotal().format();
            padLine(sb, left, right);
            if (line.hasOption()) {
                wrap(sb, "   " + line.optionGroupName() + ": ", line.optionValueName());
            }
            if (line.notes() != null && !line.notes().isBlank()) {
                wrap(sb, "   note: ", line.notes());
            }
        }
    }

    /** Subtotal through balance-due/status — the full costing, printed on the kitchen
     *  ticket so whoever hands the order over can see exactly what is owed. */
    private void totalsAndPayment(StringBuilder sb, Order order) {
        OrderTotals t = order.totals();
        padLine(sb, "Subtotal", t.subtotal().format());
        if (!t.discountTotal().isZero()) {
            padLine(sb, discountLabel(order), "-" + t.discountTotal().format());
        }
        if (!t.taxTotal().isZero()) {
            padLine(sb, "Tax", t.taxTotal().format());
        }
        if (!t.deliveryFee().isZero()) {
            padLine(sb, "Delivery fee", t.deliveryFee().format());
        }
        padLine(sb, "TOTAL", t.total().format());

        if (t.hasCashTendered()) {
            rule(sb);
            padLine(sb, "Cash", t.cashTendered().format());
            padLine(sb, "Change", t.changeDue().format());
        }

        rule(sb);
        padLine(sb, "Paid", t.amountPaid().format());
        if (!t.isFullyPaid()) {
            padLine(sb, "BALANCE DUE", t.balanceDue().format());
            center(sb, "STATUS: " + order.paymentStatus().label().toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static String discountLabel(Order order) {
        if (order.discountMode() == DiscountMode.PERCENT && order.discountRate() != null) {
            return "Discount (" + order.discountRate().stripTrailingZeros().toPlainString() + "%)";
        }
        return "Discount";
    }

    private void center(StringBuilder sb, String text) {
        if (text.length() > width) {
            // Terminates: wordWrap now guarantees every line it returns fits the width,
            // so the centre call below never re-enters this branch.
            wrapCentered(sb, text);
            return;
        }
        int pad = Math.max(0, (width - text.length()) / 2);
        sb.append(" ".repeat(pad)).append(text).append('\n');
    }

    private void wrapCentered(StringBuilder sb, String text) {
        for (String line : wordWrap(text, width)) {
            center(sb, line);
        }
    }

    private void rule(StringBuilder sb) {
        sb.append("-".repeat(width)).append('\n');
    }

    /** Right-aligns `right` after `left`, wrapping to a second line if they don't fit
     *  together — at 32 columns a line + price routinely doesn't fit on one line. */
    private void padLine(StringBuilder sb, String left, String right) {
        int space = width - left.length() - right.length();
        if (space >= 1) {
            sb.append(left).append(" ".repeat(space)).append(right).append('\n');
        } else {
            // `left` can be wider than the roll on its own — the real menu has combo
            // names near 100 characters — so it is wrapped rather than written raw and
            // silently clipped at the paper edge by the printer.
            for (String line : wordWrap(left, width)) {
                sb.append(line).append('\n');
            }
            int rightPad = Math.max(0, width - right.length());
            sb.append(" ".repeat(rightPad)).append(right).append('\n');
        }
    }

    /** Prefix + wrapped text, continuation lines indented under the prefix. */
    private void wrap(StringBuilder sb, String prefix, String text) {
        List<String> lines = wordWrap(text, Math.max(8, width - prefix.length()));
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i == 0 ? prefix : " ".repeat(prefix.length())).append(lines.get(i)).append('\n');
        }
    }

    private static List<String> wordWrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("");
            return lines;
        }
        int limit = Math.max(1, maxWidth);
        StringBuilder current = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            // A single token wider than the roll has no break opportunity, so it is cut
            // into roll-width chunks. Without this it was emitted whole and the printer
            // clipped the overflow — losing the end of long item names and of addresses
            // typed without spaces.
            while (word.length() > limit) {
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                lines.add(word.substring(0, limit));
                word = word.substring(limit);
            }
            if (current.length() > 0 && current.length() + 1 + word.length() > limit) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }
}
