package rps.db;

import rps.util.Money;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Read-only aggregate queries over existing, already-indexed order data — no new tables. */
public final class ReportsDao {

    /**
     * What counts as earned money, shared verbatim by every query below so no two reports
     * can disagree about it — and matching OrderDao#dailySummary, which is what makes the
     * Dashboard's Revenue card reconcile with this tab.
     *
     * <p>A pay-later (loan) order is included from the moment credit is given, before any
     * cash arrives: the sale happened, and the balance still owed is tracked as a
     * receivable on the Pay Later screen rather than being left out of the books. The OR
     * cannot double-count a loan that is later paid in full — a row either matches or it
     * doesn't. Cancelled orders are excluded whatever their payment state, since that money
     * goes back out.
     */
    private static final String EARNED = "(payment_status = 'PAID' OR loan_at IS NOT NULL) "
        + "AND fulfilment_status <> 'CANCELLED'";
    private static final String EARNED_CO = "(co.payment_status = 'PAID' OR co.loan_at IS NOT NULL) "
        + "AND co.fulfilment_status <> 'CANCELLED'";

    private final Db db;

    public ReportsDao(Db db) {
        this.db = db;
    }

    public record BestSeller(String itemName, long quantity, Money revenue) {}

    /**
     * Revenue here is each line's share of what the customer ACTUALLY paid, not its
     * pre-discount list value: an order-level discount is prorated across that order's
     * lines in proportion to line_total. Reporting raw SUM(line_total) overstated every
     * discounted order by its full discount (verified on real data: Rs 37,579 reported
     * against Rs 37,284 collected) and left this card unable to reconcile with Staff
     * Totals / Type Mix / Sales by Hour, which all sum the post-discount order total.
     *
     * <p>Delivery fee is deliberately NOT distributed into item revenue — it is a
     * service charge, not a sale of any menu item — so these lines sum to
     * (subtotal - discount), i.e. order total minus delivery fees. NULLIF guards the
     * degenerate zero-subtotal order (every line free) against a divide-by-zero.
     */
    public List<BestSeller> bestSellers(LocalDate from, LocalDate to, int limit) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT ol.item_name,
                           SUM(ol.quantity) AS qty,
                           round(SUM(
                               ol.line_total
                               * (co.subtotal - co.discount_total)
                               / NULLIF(co.subtotal, 0)), 2) AS revenue
                    FROM order_line ol
                    JOIN customer_order co ON co.id = ol.order_id
                    WHERE co.business_date BETWEEN ? AND ? AND """ + EARNED_CO + """
                    GROUP BY ol.item_name
                    ORDER BY qty DESC
                    LIMIT ?
                    """)) {
                ps.setObject(1, from);
                ps.setObject(2, to);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<BestSeller> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(new BestSeller(rs.getString("item_name"), rs.getLong("qty"),
                            Money.of(rs.getBigDecimal("revenue"))));
                    }
                    return result;
                }
            }
        });
    }

    public record HourBucket(int hour, long orderCount, Money revenue) {}

    public List<HourBucket> salesByHour(LocalDate from, LocalDate to) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT EXTRACT(HOUR FROM created_at)::int AS hr, COUNT(*) AS cnt, SUM(total) AS revenue
                    FROM customer_order
                    WHERE business_date BETWEEN ? AND ? AND """ + EARNED + """
                    GROUP BY hr
                    ORDER BY hr
                    """)) {
                ps.setObject(1, from);
                ps.setObject(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    List<HourBucket> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(new HourBucket(rs.getInt("hr"), rs.getLong("cnt"), Money.of(rs.getBigDecimal("revenue"))));
                    }
                    return result;
                }
            }
        });
    }

    public record StaffTotal(String staffName, long orderCount, Money revenue) {}

    public List<StaffTotal> staffTotals(LocalDate from, LocalDate to) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT staff_name, COUNT(*) AS cnt, SUM(total) AS revenue
                    FROM customer_order
                    WHERE business_date BETWEEN ? AND ? AND """ + EARNED + """
                    GROUP BY staff_name
                    ORDER BY revenue DESC
                    """)) {
                ps.setObject(1, from);
                ps.setObject(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StaffTotal> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(new StaffTotal(rs.getString("staff_name"), rs.getLong("cnt"),
                            Money.of(rs.getBigDecimal("revenue"))));
                    }
                    return result;
                }
            }
        });
    }

    public record TypeMix(String orderType, long orderCount, Money revenue) {}

    public List<TypeMix> orderTypeMix(LocalDate from, LocalDate to) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT order_type, COUNT(*) AS cnt, SUM(total) AS revenue
                    FROM customer_order
                    WHERE business_date BETWEEN ? AND ? AND """ + EARNED + """
                    GROUP BY order_type
                    ORDER BY revenue DESC
                    """)) {
                ps.setObject(1, from);
                ps.setObject(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    List<TypeMix> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(new TypeMix(rs.getString("order_type"), rs.getLong("cnt"),
                            Money.of(rs.getBigDecimal("revenue"))));
                    }
                    return result;
                }
            }
        });
    }
}
