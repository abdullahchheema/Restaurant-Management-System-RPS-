package rps.db;

import rps.model.DeliveryOrderSummary;
import rps.model.DeliveryRun;
import rps.model.DeliveryRunStatus;
import rps.model.OrderStatus;
import rps.model.OrderType;
import rps.model.Rider;
import rps.util.Money;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Riders and delivery runs — a run groups one or more DELIVERY orders under one rider
 *  while they're out, and settles them together when the rider is back (see
 *  DeliveryRun#expectedAmount / completeRun). */
public final class DeliveryDao {

    private final Db db;
    private final OrderDao orderDao;

    public DeliveryDao(Db db) {
        this.db = db;
        this.orderDao = new OrderDao(db);
    }

    // ---------------------------------------------------------------- riders

    public List<Rider> listRiders(boolean activeOnly) throws DatabaseException {
        String sql = "SELECT id, name, phone, active FROM rider "
            + (activeOnly ? "WHERE active = TRUE " : "")
            + "ORDER BY name";
        return db.inReadOnly(conn -> {
            try (var st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                List<Rider> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRider(rs));
                }
                return result;
            }
        });
    }

    public Rider createRider(String name, String phone) throws DatabaseException {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO rider (name, phone) VALUES (?, ?) RETURNING id, name, phone, active")) {
                ps.setString(1, name.trim());
                if (phone == null || phone.isBlank()) ps.setNull(2, Types.VARCHAR); else ps.setString(2, phone.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return mapRider(rs);
                }
            }
        });
    }

    public void setRiderActive(int riderId, boolean active) throws DatabaseException {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE rider SET active = ? WHERE id = ?")) {
                ps.setBoolean(1, active);
                ps.setInt(2, riderId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private Rider mapRider(ResultSet rs) throws SQLException {
        return new Rider(rs.getInt("id"), rs.getString("name"), rs.getString("phone"), rs.getBoolean("active"));
    }

    // ---------------------------------------------------------------- eligible orders

    /** Orders not already on a run, in either state a run can carry (see DeliveryRun's
     *  javadoc: Pending — still owes money — or already Payment Received, e.g.
     *  prepaid, but still needing physical delivery). Not restricted to
     *  order_type = DELIVERY — a rider can just as well carry a Takeaway or Dine-in
     *  order out, so any type is eligible. */
    public List<DeliveryOrderSummary> listUnassignedOrders(LocalDate from, LocalDate to) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, order_number, order_type, status, total, amount_paid, customer_name, delivery_address,
                           %s AS items_summary
                    FROM customer_order co
                    WHERE delivery_run_id IS NULL
                      AND status IN ('PENDING', 'PARTIALLY_PAID', 'PAYMENT_RECEIVED')
                      AND business_date BETWEEN ? AND ?
                    ORDER BY (status IN ('PENDING','PARTIALLY_PAID')) DESC, created_at DESC
                    """.formatted(ITEMS_SUMMARY_SQL))) {
                ps.setObject(1, from);
                ps.setObject(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    List<DeliveryOrderSummary> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(mapOrderSummary(rs));
                    }
                    return result;
                }
            }
        });
    }

    /** Correlated subquery reused everywhere an order needs to show what's actually in
     *  it (e.g. "2x Chicken Tikka Pizza (Large), 1x Coca-Cola") — a rider handing off an
     *  order, or the cashier assigning one, needs to see contents, not just a total. */
    private static final String ITEMS_SUMMARY_SQL = """
        (SELECT string_agg(ol.quantity || 'x ' || ol.item_name, ', ' ORDER BY ol.line_no)
         FROM order_line ol WHERE ol.order_id = co.id)
        """;

    private DeliveryOrderSummary mapOrderSummary(ResultSet rs) throws SQLException {
        Money total = Money.of(rs.getBigDecimal("total"));
        Money paid = Money.of(rs.getBigDecimal("amount_paid"));
        return new DeliveryOrderSummary(
            rs.getLong("id"),
            rs.getString("order_number"),
            OrderType.valueOf(rs.getString("order_type")),
            OrderStatus.valueOf(rs.getString("status")),
            total,
            total.subtractClamped(paid),
            rs.getString("customer_name"),
            rs.getString("delivery_address"),
            rs.getString("items_summary")
        );
    }

    // ---------------------------------------------------------------- runs

    /** Assigns the given orders to a brand-new run for this rider, in one
     *  transaction — if any order was claimed by another run (or stopped being
     *  eligible) between the cashier picking it and clicking Start, the whole run is
     *  rolled back rather than starting with a silently smaller order list. */
    public DeliveryRun startRun(int riderId, List<Long> orderIds, int staffId, String staffName) throws DatabaseException {
        if (orderIds.isEmpty()) {
            throw new DatabaseException("Select at least one order to assign.");
        }
        return db.inTransaction(conn -> {
            long runId;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO delivery_run (rider_id, staff_id, staff_name) VALUES (?, ?, ?) RETURNING id
                    """)) {
                ps.setInt(1, riderId);
                ps.setInt(2, staffId);
                ps.setString(3, staffName);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    runId = rs.getLong(1);
                }
            }

            Long[] ids = orderIds.toArray(new Long[0]);
            int updated;
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE customer_order SET delivery_run_id = ?
                    WHERE id = ANY (?) AND delivery_run_id IS NULL
                      AND status IN ('PENDING', 'PARTIALLY_PAID', 'PAYMENT_RECEIVED')
                    """)) {
                ps.setLong(1, runId);
                Array arr = conn.createArrayOf("int8", ids);
                ps.setArray(2, arr);
                updated = ps.executeUpdate();
            }
            if (updated != orderIds.size()) {
                throw new DatabaseException("One or more selected orders are no longer available to "
                    + "assign (already on another run, or no longer Pending/Payment Received). Refresh and try again.");
            }

            return loadRun(conn, runId);
        });
    }

    /** Pulls a single order back off an OPEN run — e.g. it was added by mistake, or
     *  the rider can't take it after all. The order keeps whatever status it already
     *  had; only the assignment is undone. */
    public boolean removeOrderFromRun(long runId, long orderId) throws DatabaseException {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE customer_order SET delivery_run_id = NULL
                    WHERE id = ? AND delivery_run_id = ?
                      AND EXISTS (SELECT 1 FROM delivery_run WHERE id = ? AND status = 'OPEN')
                    """)) {
                ps.setLong(1, orderId);
                ps.setLong(2, runId);
                ps.setLong(3, runId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Records a payment against ONE order on a run, without touching the run itself —
     * for when the rider comes back with less than the full expected amount and the
     * cashier knows which specific customers actually paid. Settling every order this
     * way leaves the run with nothing left to collect, so it then closes out cleanly.
     * Delegates to OrderDao so the status ladder (Pending → Partially Paid → Payment
     * Received) and the amount_paid clamp behave identically to a Dashboard payment.
     */
    public boolean recordPaymentForOrder(long orderId, Money amount, int staffId) throws DatabaseException {
        return orderDao.recordPayment(orderId, amount, staffId);
    }

    /**
     * Closes an OPEN run that came back short, leaving its still-unpaid orders unpaid.
     * Used when some customers genuinely didn't pay and the rider isn't going back —
     * the run is over, but the money is still owed.
     *
     * <p>Unlike cancelRun, the orders stay ATTACHED to the run: the run becomes the
     * historical record of what was actually taken out, and detaching them would erase
     * that. They keep their Pending/Partially Paid status, so they still show up in the
     * Dashboard's unpaid pane and in Outstanding, and can be collected there later.
     */
    public boolean closeRunWithUnpaid(long runId, Money collectedAmount, int staffId) throws DatabaseException {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE delivery_run SET status = 'COMPLETED', collected_amount = ?, closed_at = now()
                    WHERE id = ? AND status = 'OPEN'
                    """)) {
                ps.setBigDecimal(1, (collectedAmount == null ? Money.ZERO : collectedAmount).asBigDecimal());
                ps.setLong(2, runId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Cancels the whole run — the rider never went out, or the assignment was a
     *  mistake. Every assigned order is released back to unassigned; none of their
     *  own statuses are touched. */
    public boolean cancelRun(long runId) throws DatabaseException {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE delivery_run SET status = 'CANCELLED', closed_at = now() WHERE id = ? AND status = 'OPEN'")) {
                ps.setLong(1, runId);
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE customer_order SET delivery_run_id = NULL WHERE delivery_run_id = ?")) {
                ps.setLong(1, runId);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /**
     * Settles a run: if {@code collectedAmount} covers the outstanding balance on
     * every still-Pending order in the run (an already-Payment-Received order in the
     * run, e.g. prepaid, contributes nothing to that expectation), each Pending order
     * is fully settled to Payment Received — via the exact same status-derivation
     * OrderDao uses for a direct payment, not a re-implementation of it — and the run
     * is marked Completed with the amount actually handed over. A shortfall rolls
     * back the whole thing rather than guessing which order(s) it should apply to;
     * the cashier re-checks with the rider and tries again.
     */
    public DeliveryRun completeRun(long runId, Money collectedAmount, int staffId) throws DatabaseException {
        if (collectedAmount == null || collectedAmount.isNegative()) {
            throw new DatabaseException("Enter a valid collected amount.");
        }
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM delivery_run WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new DatabaseException("This delivery run no longer exists.");
                    if (!"OPEN".equals(rs.getString(1))) {
                        throw new DatabaseException("This delivery run is no longer open.");
                    }
                }
            }

            // Each unsettled order's balance AND the status it currently holds — the
            // status has to be carried through to applyPayment as the "from" state, or a
            // PARTIALLY_PAID order would be logged in order_status_history as though it
            // had transitioned from PENDING.
            record Unsettled(Money balance, OrderStatus status) {}
            Map<Long, Unsettled> unsettledByOrder = new LinkedHashMap<>();
            Money expected = Money.ZERO;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, status, total, amount_paid FROM customer_order
                    WHERE delivery_run_id = ? AND status IN ('PENDING','PARTIALLY_PAID')
                    FOR UPDATE
                    """)) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Money total = Money.of(rs.getBigDecimal("total"));
                        Money paid = Money.of(rs.getBigDecimal("amount_paid"));
                        Money balance = total.subtractClamped(paid);
                        unsettledByOrder.put(rs.getLong("id"),
                            new Unsettled(balance, OrderStatus.valueOf(rs.getString("status"))));
                        expected = expected.add(balance);
                    }
                }
            }

            if (collectedAmount.compareTo(expected) < 0) {
                throw new DatabaseException("Collected amount (" + collectedAmount.format()
                    + ") is short of the expected " + expected.format() + ". Not settled.");
            }

            for (Map.Entry<Long, Unsettled> e : unsettledByOrder.entrySet()) {
                orderDao.applyPayment(conn, e.getKey(), e.getValue().balance(), staffId, e.getValue().status());
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE delivery_run SET status = 'COMPLETED', collected_amount = ?, closed_at = now()
                    WHERE id = ?
                    """)) {
                ps.setBigDecimal(1, collectedAmount.asBigDecimal());
                ps.setLong(2, runId);
                ps.executeUpdate();
            }

            return loadRun(conn, runId);
        });
    }

    public DeliveryRun loadRun(long runId) throws DatabaseException {
        return db.inReadOnly(conn -> loadRun(conn, runId));
    }

    private DeliveryRun loadRun(Connection conn, long runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(RUN_QUERY + " WHERE dr.id = ? ORDER BY co.created_at")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<Long, DeliveryRun> runs = assembleRuns(rs);
                return runs.get(runId);
            }
        }
    }

    /** Every OPEN run regardless of when it started — a rider still out from
     *  yesterday is still operationally relevant today, so this deliberately ignores
     *  whatever date range the history view is browsing. */
    public List<DeliveryRun> listOpenRuns() throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    RUN_QUERY + " WHERE dr.status = 'OPEN' ORDER BY dr.created_at, co.created_at")) {
                try (ResultSet rs = ps.executeQuery()) {
                    return new ArrayList<>(assembleRuns(rs).values());
                }
            }
        });
    }

    /** Every COMPLETED or CANCELLED run closed within the given date range, each with
     *  its assigned orders — used for the Deliveries screen's history list, which is
     *  filtered separately from the always-shown open runs (see listOpenRuns). */
    public List<DeliveryRun> listClosedRuns(LocalDate from, LocalDate to) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    RUN_QUERY + " WHERE dr.status <> 'OPEN' AND dr.closed_at::date BETWEEN ? AND ? "
                        + "ORDER BY dr.closed_at DESC, co.created_at")) {
                ps.setObject(1, from);
                ps.setObject(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    return new ArrayList<>(assembleRuns(rs).values());
                }
            }
        });
    }

    /** Cheap change-detection for a poll: count + latest state-changing timestamp. */
    // No fingerprint/short-circuit here (unlike OrderDao/MenuDao's pollers): an
    // assigned order's own balance can change from elsewhere (a Dashboard "Record
    // Payment" on an order that also happens to be on an open run) without touching
    // delivery_run itself, so a timestamp-based fingerprint on that table alone could
    // go stale. Run/order volume here is small enough that just re-querying on every
    // poll tick is cheap and always correct.

    private static final String RUN_QUERY = """
        SELECT dr.id AS run_id, dr.rider_id, r.name AS rider_name, dr.status AS run_status,
               dr.collected_amount, dr.staff_id, dr.staff_name, dr.created_at, dr.closed_at,
               co.id AS order_id, co.order_number, co.order_type, co.status AS order_status,
               co.total, co.amount_paid, co.customer_name, co.delivery_address,
               %s AS items_summary
        FROM delivery_run dr
        JOIN rider r ON r.id = dr.rider_id
        LEFT JOIN customer_order co ON co.delivery_run_id = dr.id
        """.formatted(ITEMS_SUMMARY_SQL);

    private Map<Long, DeliveryRun> assembleRuns(ResultSet rs) throws SQLException {
        record Header(int riderId, String riderName, DeliveryRunStatus status, Money collectedAmount,
                       Integer staffId, String staffName, OffsetDateTime createdAt, OffsetDateTime closedAt) {}

        Map<Long, Header> headers = new LinkedHashMap<>();
        Map<Long, List<DeliveryOrderSummary>> ordersByRun = new LinkedHashMap<>();

        while (rs.next()) {
            long runId = rs.getLong("run_id");
            headers.putIfAbsent(runId, new Header(
                rs.getInt("rider_id"),
                rs.getString("rider_name"),
                DeliveryRunStatus.valueOf(rs.getString("run_status")),
                rs.getBigDecimal("collected_amount") == null ? null : Money.of(rs.getBigDecimal("collected_amount")),
                (Integer) rs.getObject("staff_id"),
                rs.getString("staff_name"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("closed_at"))
            ));
            ordersByRun.computeIfAbsent(runId, k -> new ArrayList<>());
            long orderId = rs.getLong("order_id");
            if (!rs.wasNull()) {
                Money total = Money.of(rs.getBigDecimal("total"));
                Money paid = Money.of(rs.getBigDecimal("amount_paid"));
                ordersByRun.get(runId).add(new DeliveryOrderSummary(
                    orderId,
                    rs.getString("order_number"),
                    OrderType.valueOf(rs.getString("order_type")),
                    OrderStatus.valueOf(rs.getString("order_status")),
                    total,
                    total.subtractClamped(paid),
                    rs.getString("customer_name"),
                    rs.getString("delivery_address"),
                    rs.getString("items_summary")
                ));
            }
        }

        Map<Long, DeliveryRun> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Header> e : headers.entrySet()) {
            Header h = e.getValue();
            result.put(e.getKey(), new DeliveryRun(e.getKey(), h.riderId(), h.riderName(), h.status(),
                h.collectedAmount(), h.staffId(), h.staffName(), h.createdAt(), h.closedAt(),
                ordersByRun.get(e.getKey())));
        }
        return result;
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atZone(ZonedDateTime.now().getZone()).toOffsetDateTime();
    }
}
