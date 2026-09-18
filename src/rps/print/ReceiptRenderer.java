package rps.print;

import rps.model.DiscountMode;
import rps.model.Order;
import rps.model.OrderLine;
import rps.model.OrderTotals;
import rps.util.AppSettings;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds both receipt documents from an Order already re-read from the database after
 * commit, so the two documents and the stored record can never disagree. Width is a
 * parameter (columns), not hardcoded — 48 for an 80mm roll, 32 for 58mm — so a receipt
 * printer of either size lays out correctly (see plan Phase D).
 */
public final class ReceiptRenderer {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", java.util.Locale.ENGLISH);

    private final int width;

    public ReceiptRenderer(int width) {
        this.width = width;
    }

    /** Fixed vertical clearance at the top of both receipts for a future raster logo —
     *  a plain-text thermal driver can't print an image today, but reserving this space
     *  now means adding one later doesn't reflow anything below it. */
    private void logoBlock(StringBuilder sb) {
        sb.append('\n').append('\n');
    }

    /** Kitchen ticket: names, sizes, quantities, notes, order type. No prices. */
    public String kitchenTicket(Order order) {
        StringBuilder sb = new StringBuilder();
        logoBlock(sb);
        center(sb, "KITCHEN TICKET");
        rule(sb);
        sb.append("Order: ").append(order.orderNumber()).append('\n');
        sb.append("Type:  ").append(order.type().label()).append('\n');
        sb.append("Time:  ").append(order.createdAt().format(TS)).append('\n');
        if (order.tableNumber() != null && !order.tableNumber().isBlank()) {
            sb.append("Table: ").append(order.tableNumber()).append('\n');
        }
        if (order.isDelivery()) {
            wrap(sb, "Deliver to: ", order.deliveryAddress());
            sb.append("Phone: ").append(order.customerPhone()).append('\n');
        }
        rule(sb);
        for (OrderLine line : order.lines()) {
            // Wrapped, not raw: this is the line the kitchen actually cooks from, so a
            // long combo name being clipped at the paper edge means the wrong food.
            wrap(sb, String.format("%2dx ", line.quantity()), line.displayName());
            if (line.hasOption()) {
                wrap(sb, "   " + line.optionGroupName() + ": ", line.optionValueName());
            }
            if (line.notes() != null && !line.notes().isBlank()) {
                wrap(sb, "   note: ", line.notes());
            }
        }
        rule(sb);
        if (order.notes() != null && !order.notes().isBlank()) {
            wrap(sb, "Notes: ", order.notes());
        }
        return sb.toString();
    }

    /** Customer receipt: itemised with prices, subtotal, discount, total, cash/change. */
    public String customerReceipt(Order order) {
        AppSettings settings = AppSettings.get();
        StringBuilder sb = new StringBuilder();
        logoBlock(sb);
        center(sb, settings.shopName().toUpperCase(java.util.Locale.ROOT));
        if (!settings.shopAddress().isBlank()) {
            wrapCentered(sb, settings.shopAddress());
        }
        if (!settings.shopPhone().isBlank()) {
            center(sb, settings.shopPhone());
        }
        rule(sb);
        sb.append("Order:  ").append(order.orderNumber()).append('\n');
        sb.append("Date:   ").append(order.createdAt().format(TS)).append('\n');
        sb.append("Type:   ").append(order.type().label()).append('\n');
        if (order.tableNumber() != null && !order.tableNumber().isBlank()) {
            sb.append("Table:  ").append(order.tableNumber()).append('\n');
        }
        if (order.isDelivery()) {
            wrap(sb, "Deliver to: ", order.deliveryAddress());
            sb.append("Phone:  ").append(order.customerPhone()).append('\n');
        }
        rule(sb);
        for (OrderLine line : order.lines()) {
            String left = line.quantity() + "x " + line.displayName();
            String right = line.lineTotal().format();
            padLine(sb, left, right);
            if (line.hasOption()) {
                wrap(sb, "   " + line.optionGroupName() + ": ", line.optionValueName());
            }
        }
        rule(sb);

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
            center(sb, "STATUS: " + order.status().label().toUpperCase(java.util.Locale.ROOT));
        }

        rule(sb);
        if (!settings.receiptFooter().isBlank()) {
            wrapCentered(sb, settings.receiptFooter());
        }
        return sb.toString();
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
