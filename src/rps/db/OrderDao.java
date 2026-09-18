package rps.db;

import rps.model.DiscountMode;
import rps.model.DraftLine;
import rps.model.Order;
import rps.model.OrderDraft;
import rps.model.OrderLine;
import rps.model.OrderStatus;
import rps.model.OrderTotals;
import rps.model.OrderType;
import rps.util.Money;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OrderDao {

    /** Orders before this hour belong to the previous business day. */
    private static final LocalTime BUSINESS_DAY_CUTOVER = LocalTime.of(4, 0);

    private final Db db;

    public OrderDao(Db db) {
        this.db = db;
    }

    public static LocalDate businessDate(ZonedDateTime now) {
        return now.toLocalTime().isBefore(BUSINESS_DAY_CUTOVER)
            ? now.toLocalDate().minusDays(1)
            : now.toLocalDate();
    }

    /** Whether a confirmed order can still be edited from the dashboard — anything short
     *  of CANCELLED (a cancelled order has nothing left to edit) and within the
     *  configurable edit window of its creation time. A PENDING order is editable, same
     *  as a PAYMENT_RECEIVED one — editing it may raise or lower its total and therefore
     *  its status, which updateOrder re-derives after saving. Time-based, so it can only
     *  be enforced here and in updateOrder itself — Postgres CHECK constraints reject
     *  non-immutable functions like now(), so this can't also live as a DB constraint the
     *  way phone-required or the discount clamp do. */
    public static boolean canEdit(Order order) {
        if (order.status() == OrderStatus.CANCELLED) return false;
        int windowMinutes = rps.util.AppSettings.get().orderEditWindowMinutes();
        OffsetDateTime deadline = order.createdAt().plusMinutes(windowMinutes);
        // OffsetDateTime.isBefore compares by instant, not local wall-clock, so the two
        // offsets don't need to match for this to be correct.
        return OffsetDateTime.now().isBefore(deadline);
    }

    private record PricedVariant(int variantId, int menuItemId, String itemName, String sizeLabel, Money price) {}
    private record PricedOption(int optionValueId, int groupId, String groupName, String valueName) {}

    /**
     * Saves an order and all of its lines atomically: prices are re-read server-side,
     * the order number comes from the daily counter, lines are batch-inserted, and the
     * stored totals (including discount) are rolled up from the rows actually written
     * and the discount mode/value in ONE SQL statement — never summed or discounted in
     * Java — so the exact-equality CHECK on customer_order can never be violated by a
     * Java/SQL rounding disagreement. The order is saved PENDING; a full (or overpaid)
     * cash amount immediately settles it to PAYMENT_RECEIVED, a short or absent amount
     * leaves it PENDING for a later rps.db.OrderDao#recordPayment. Returns the order
     * re-read from the database, which both receipts render from.
     */
    public Order saveOrder(OrderDraft liveDraft, int staffId, String staffName) throws DatabaseException {
        // Defence in depth: our own immutable copy before anything reads it. The UI also
        // snapshots on the EDT, but this method's body runs on a background thread and a
        // caller passing a still-live draft is a data race — measured at roughly one
        // ConcurrentModificationException per 60 concurrent runs, and far more often
        // orders silently saved with lines added AFTER the cashier confirmed.
        final OrderDraft draft = liveDraft.snapshot();
        if (draft.isEmpty()) {
            throw new DatabaseException("Cannot confirm an empty order.");
        }
        if (!draft.isCustomerInfoValid()) {
            throw new DatabaseException(customerInfoErrorMessage(draft));
        }

        LocalDate bizDate = businessDate(ZonedDateTime.now());
        DiscountMode mode = draft.discountMode();
        BigDecimal discountValue = draft.discountValue();
        Money cashPaid = draft.cashTendered();

        return db.inTransaction(conn -> {
            Map<Integer, PricedVariant> priced = fetchOrderableVariants(conn, draft.variantIds());
            for (Integer vid : draft.variantIds()) {
                if (!priced.containsKey(vid)) {
                    throw new DatabaseException("An item in this order is no longer available.");
                }
            }
            Map<Integer, PricedOption> pricedOptions = fetchOrderableOptions(conn, draft.optionValueIds());
            validateLineOptions(conn, draft.lines(), priced, pricedOptions);

            int seq = nextDailySequence(conn, bizDate);
            // Locale.ROOT on the sequence is load-bearing, not cosmetic: under a locale
            // with a non-Western default number system (ar, bn, ...) a bare %03d emits
            // Eastern Arabic digits, so this identifier -- stored, printed, searched and
            // used as the receipt filename -- would stop matching \d{8}-\d{3} entirely.
            String orderNumber = bizDate.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + String.format(java.util.Locale.ROOT, "%03d", seq);

            long orderId;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO customer_order
                        (order_number, business_date, order_type, status, staff_id, staff_name,
                         table_number, customer_name, customer_phone, delivery_address, notes, completed_at,
                         discount_mode, discount_rate)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?, NULL, ?, ?)
                    RETURNING id
                    """)) {
                ps.setString(1, orderNumber);
                ps.setObject(2, bizDate);
                ps.setString(3, draft.type().name());
                ps.setString(4, OrderStatus.PENDING.name());
                ps.setInt(5, staffId);
                ps.setString(6, staffName);
                setNullableString(ps, 7, draft.tableNumber());
                setNullableString(ps, 8, draft.customerName());
                setNullableString(ps, 9, rps.util.Validators.normalizePhone(draft.customerPhone()));
                setNullableString(ps, 10, draft.deliveryAddress());
                setNullableString(ps, 11, draft.notes());
                ps.setString(12, mode.name());
                if (mode == DiscountMode.PERCENT && discountValue != null) {
                    ps.setBigDecimal(13, discountValue);
                } else {
                    ps.setNull(13, Types.NUMERIC);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    orderId = rs.getLong(1);
                }
            }

            insertLines(conn, orderId, draft.lines(), priced, pricedOptions);
            rollupTotals(conn, orderId, draft.type(), mode, discountValue);

            applyPayment(conn, orderId, cashPaid, staffId, null);

            return loadOrderForPrint(conn, orderId);
        });
    }

    /**
     * Applies edits to an already-confirmed order: replaces its lines wholesale and
     * updates type/contact/discount, then re-runs the exact same rollupTotals used at
     * confirm time so the edited order's totals are computed identically, never by
     * adjusting the old figures in Java. order_number, business_date, staff_id/name, and
     * created_at are the order's original identity and are never touched — this is the
     * same order, not a new one. The edit window is re-checked inside this transaction
     * (not just by the caller before opening the dialog) to close the gap between a
     * cashier opening the editor and clicking Save several minutes later. Status is
     * re-derived from amount_paid against the freshly-rolled-up total: an edit that
     * raises the total can move a paid order back to Pending, and one that lowers it
     * (or an additional cash amount entered in the editor) can settle a pending order.
     */
    public Order updateOrder(long orderId, OrderDraft liveDraft, int staffId, String staffName) throws DatabaseException {
        final OrderDraft draft = liveDraft.snapshot();   // same reasoning as saveOrder
        if (draft.isEmpty()) {
            throw new DatabaseException("An order must have at least one item.");
        }
        if (!draft.isCustomerInfoValid()) {
            throw new DatabaseException(customerInfoErrorMessage(draft));
        }

        DiscountMode mode = draft.discountMode();
        BigDecimal discountValue = draft.discountValue();
        Money cashPaid = draft.cashTendered();

        return db.inTransaction(conn -> {
            Order current = loadOrderForPrint(conn, orderId);
            if (current == null) {
                throw new DatabaseException("This order no longer exists.");
            }
            if (!canEdit(current)) {
                throw new DatabaseException("This order can no longer be edited — the "
                    + rps.util.AppSettings.get().orderEditWindowMinutes() + "-minute edit window has passed.");
            }

            Map<Integer, PricedVariant> priced = fetchOrderableVariants(conn, draft.variantIds());
            for (Integer vid : draft.variantIds()) {
                if (!priced.containsKey(vid)) {
                    throw new DatabaseException("An item in this order is no longer available.");
                }
            }
            Map<Integer, PricedOption> pricedOptions = fetchOrderableOptions(conn, draft.optionValueIds());
            validateLineOptions(conn, draft.lines(), priced, pricedOptions);

            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE customer_order
                    SET order_type = ?, table_number = ?, customer_name = ?, customer_phone = ?,
                        delivery_address = ?, notes = ?, discount_mode = ?, discount_rate = ?
                    WHERE id = ?
                    """)) {
                ps.setString(1, draft.type().name());
                setNullableString(ps, 2, draft.tableNumber());
                setNullableString(ps, 3, draft.customerName());
                setNullableString(ps, 4, rps.util.Validators.normalizePhone(draft.customerPhone()));
                setNullableString(ps, 5, draft.deliveryAddress());
                setNullableString(ps, 6, draft.notes());
                ps.setString(7, mode.name());
                if (mode == DiscountMode.PERCENT && discountValue != null) {
                    ps.setBigDecimal(8, discountValue);
                } else {
                    ps.setNull(8, Types.NUMERIC);
                }
                ps.setLong(9, orderId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM order_line WHERE order_id = ?")) {
                ps.setLong(1, orderId);
                ps.executeUpdate();
            }

            insertLines(conn, orderId, draft.lines(), priced, pricedOptions);
            rollupTotals(conn, orderId, draft.type(), mode, discountValue);

            applyPayment(conn, orderId, cashPaid, staffId, current.status());

            return loadOrderForPrint(conn, orderId);
        });
    }

    private void insertLines(Connection conn, long orderId, List<DraftLine> lines,
                              Map<Integer, PricedVariant> priced, Map<Integer, PricedOption> pricedOptions)
            throws SQLException, DatabaseException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO order_line
                    (order_id, line_no, menu_item_id, variant_id, item_name, size_label, unit_price, quantity,
                     notes, option_value_id, option_group_name, option_value_name)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            int lineNo = 0;
            for (DraftLine l : lines) {
                PricedVariant v = priced.get(l.variantId());
                PricedOption o = l.optionValueId() == null ? null : pricedOptions.get(l.optionValueId());
                ps.setLong(1, orderId);
                ps.setInt(2, ++lineNo);
                ps.setInt(3, v.menuItemId());
                ps.setInt(4, v.variantId());
                ps.setString(5, v.itemName());
                setNullableString(ps, 6, v.sizeLabel());
                ps.setBigDecimal(7, v.price().asBigDecimal());
                ps.setInt(8, l.quantity());
                setNullableString(ps, 9, l.notes());
                if (o == null) {
                    ps.setNull(10, Types.INTEGER);
                    ps.setNull(11, Types.VARCHAR);
                    ps.setNull(12, Types.VARCHAR);
                } else {
                    ps.setInt(10, o.optionValueId());
                    ps.setString(11, o.groupName());
                    ps.setString(12, o.valueName());
                }
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            for (int c : counts) {
                if (c != 1 && c != Statement.SUCCESS_NO_INFO) {
                    throw new DatabaseException("Failed to save an order line.");
                }
            }
        }
    }

    /** Confirms each line's chosen option (if any) actually belongs to the option group
     *  attached to that line's menu item — a client-supplied optionValueId is never
     *  trusted to be the right one for the item it was picked on, exactly as prices are
     *  never trusted. */
    private void validateLineOptions(Connection conn, List<DraftLine> lines,
                                      Map<Integer, PricedVariant> priced, Map<Integer, PricedOption> pricedOptions)
            throws SQLException, DatabaseException {
        for (DraftLine l : lines) {
            PricedVariant v = priced.get(l.variantId());
            Integer expectedGroupId = itemOptionGroupId(conn, v.menuItemId());

            if (l.optionValueId() == null) {
                // "Selection required" was enforced only in the picker dialog, so any
                // other path into saveOrder/updateOrder could store a line with no
                // option — and the kitchen ticket would then not say which variant to
                // actually make, which is the whole point of the feature. Only require
                // it when the attached group still HAS a selectable value, so an item
                // whose group was emptied out doesn't become impossible to order (the
                // same condition MenuItem.hasOptions() uses to decide whether to prompt).
                if (expectedGroupId != null && groupHasSelectableValues(conn, expectedGroupId)) {
                    throw new DatabaseException("\"" + v.itemName()
                        + "\" needs an option to be chosen before it can be ordered.");
                }
                continue;
            }

            PricedOption o = pricedOptions.get(l.optionValueId());
            if (o == null) {
                throw new DatabaseException("An option in this order is no longer available.");
            }
            if (expectedGroupId == null || expectedGroupId != o.groupId()) {
                throw new DatabaseException("An option in this order no longer applies to that item.");
            }
        }
    }

    private boolean groupHasSelectableValues(Connection conn, int groupId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM option_value ov JOIN option_group og ON og.id = ov.option_group_id
                    WHERE ov.option_group_id = ? AND ov.deleted_at IS NULL AND ov.available AND og.active)
                """)) {
            ps.setInt(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private Integer itemOptionGroupId(Connection conn, int menuItemId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT option_group_id FROM menu_item WHERE id = ?")) {
            ps.setInt(1, menuItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int groupId = rs.getInt(1);
                return rs.wasNull() ? null : groupId;
            }
        }
    }

    private Map<Integer, PricedOption> fetchOrderableOptions(Connection conn, List<Integer> optionValueIds)
            throws SQLException {
        Map<Integer, PricedOption> result = new HashMap<>();
        if (optionValueIds.isEmpty()) return result;
        Integer[] ids = optionValueIds.toArray(new Integer[0]);
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT ov.id, ov.option_group_id, og.name AS group_name, ov.name AS value_name
                FROM option_value ov JOIN option_group og ON og.id = ov.option_group_id
                WHERE ov.id = ANY (?)
                  AND ov.deleted_at IS NULL AND ov.available AND og.active
                """)) {
            Array arr = conn.createArrayOf("int4", ids);
            ps.setArray(1, arr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    result.put(id, new PricedOption(id, rs.getInt("option_group_id"),
                        rs.getString("group_name"), rs.getString("value_name")));
                }
            }
        }
        return result;
    }

    private Map<Integer, PricedVariant> fetchOrderableVariants(Connection conn, List<Integer> variantIds) throws SQLException {
        Map<Integer, PricedVariant> result = new HashMap<>();
        if (variantIds.isEmpty()) return result;
        Integer[] ids = variantIds.toArray(new Integer[0]);
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT v.id, v.menu_item_id, mi.name, v.size_label, v.price
                FROM menu_item_variant v JOIN menu_item mi ON mi.id = v.menu_item_id
                WHERE v.id = ANY (?)
                  AND v.deleted_at IS NULL AND v.available
                  AND mi.deleted_at IS NULL AND mi.available
                """)) {
            Array arr = conn.createArrayOf("int4", ids);
            ps.setArray(1, arr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int vid = rs.getInt("id");
                    result.put(vid, new PricedVariant(vid, rs.getInt("menu_item_id"), rs.getString("name"),
                        rs.getString("size_label"), Money.of(rs.getBigDecimal("price"))));
                }
            }
        }
        return result;
    }

    private int nextDailySequence(Connection conn, LocalDate bizDate) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO daily_counter (business_date, last_seq) VALUES (?, 1)
                ON CONFLICT (business_date) DO UPDATE SET last_seq = daily_counter.last_seq + 1
                RETURNING last_seq
                """)) {
            ps.setObject(1, bizDate);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Rolls subtotal/discount_total/delivery_fee/total up from the order_line rows
     * actually stored, applying the discount AND the delivery fee in the SAME statement
     * that sets total — so the exact-equality CHECK (total = subtotal - discount_total +
     * tax_total + delivery_fee) is satisfied by construction, not by hoping Java's
     * BigDecimal rounding agrees with Postgres'. The discount is clamped so it can never
     * exceed the subtotal (LEAST) or go negative (GREATEST). The delivery fee threshold
     * and amount are read from AppSettings (not the database — this app has no shared,
     * synced settings store; every configurable value lives in the local settings file,
     * same as shop name/address/printer) and bound as parameters, same pattern as the
     * discount value.
     */
    private void rollupTotals(Connection conn, long orderId, OrderType orderType, DiscountMode mode,
                               BigDecimal discountValue) throws SQLException {
        rps.util.AppSettings settings = rps.util.AppSettings.get();
        try (PreparedStatement ps = conn.prepareStatement("""
                WITH s AS (
                    SELECT COALESCE(SUM(line_total), 0)::numeric(12,2) AS sub
                    FROM order_line WHERE order_id = ?
                ),
                p AS (
                    SELECT ?::text AS mode, COALESCE(?::numeric, 0) AS val
                ),
                dl AS (
                    SELECT ?::text AS otype, ?::numeric AS threshold, ?::numeric AS fee
                ),
                d AS (
                    SELECT s.sub,
                           LEAST(
                             GREATEST(
                               CASE p.mode
                                 WHEN 'PERCENT' THEN round(s.sub * p.val / 100, 2)
                                 WHEN 'AMOUNT'  THEN round(p.val, 2)
                                 ELSE 0::numeric
                               END,
                             0::numeric),
                           s.sub)::numeric(12,2) AS disc,
                           CASE WHEN dl.otype = 'DELIVERY' AND s.sub < dl.threshold
                                THEN dl.fee ELSE 0::numeric END AS dfee
                    FROM s, p, dl
                )
                UPDATE customer_order o
                SET subtotal = d.sub, discount_total = d.disc, delivery_fee = d.dfee,
                    total = d.sub - d.disc + o.tax_total + d.dfee
                FROM d
                WHERE o.id = ?
                """)) {
            ps.setLong(1, orderId);
            ps.setString(2, mode.name());
            // Bind as BigDecimal, never double/float — round(double precision, integer)
            // does not exist in Postgres and would fail this statement outright.
            if (discountValue != null) {
                ps.setBigDecimal(3, discountValue);
            } else {
                ps.setNull(3, Types.NUMERIC);
            }
            ps.setString(4, orderType.name());
            ps.setBigDecimal(5, BigDecimal.valueOf(settings.deliveryFeeThreshold()));
            ps.setBigDecimal(6, BigDecimal.valueOf(settings.deliveryFeeAmount()));
            ps.setLong(7, orderId);
            ps.executeUpdate();
        }
    }

    private Money readTotal(Connection conn, long orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT total FROM customer_order WHERE id = ?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Money.of(rs.getBigDecimal(1));
            }
        }
    }

    private Money readAmountPaid(Connection conn, long orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT amount_paid FROM customer_order WHERE id = ?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Money.of(rs.getBigDecimal(1));
            }
        }
    }

    /**
     * Applies a cash amount collected at confirm/edit time to amount_paid (capped at the
     * server total — any excess is change, not extra revenue), accumulates cash_tendered
     * so the receipt's Cash/Change lines reflect everything handed over across all
     * payments, and derives the resulting status: PAYMENT_RECEIVED once amount_paid
     * reaches total, otherwise PENDING. {@code fromStatus} is null for a brand-new order
     * (no prior status to transition from in the history log); for an edit it's the
     * order's status before this save, so the transition is recorded even when the
     * amount entered is null (e.g. the total changed enough on its own to cross the
     * paid/pending line).
     *
     * <p>Package-private (not private) so DeliveryDao can settle each order in a
     * completed delivery run against the same connection/transaction it's already
     * running, rather than re-implementing this same status-derivation logic.
     */
    OrderStatus applyPayment(Connection conn, long orderId, Money cashPaid, Integer staffId,
                              OrderStatus fromStatus) throws SQLException {
        Money serverTotal = readTotal(conn, orderId);
        Money alreadyPaid = readAmountPaid(conn, orderId);

        Money combined = cashPaid == null ? alreadyPaid : alreadyPaid.add(cashPaid);
        // Clamp against the CURRENT total on every call, not only when new cash arrives.
        // updateOrder re-runs rollupTotals, so an edit that removes items lowers the
        // total — and amount_paid left sitting above it recorded the customer as having
        // paid more than the order is worth (seen live: Rs 33,000 paid against a Rs
        // 27,300 total after an edit). The excess is change owed and belongs in
        // cash_tendered, which is what drives the receipt's Change line; amount_paid is
        // strictly "credited to this order" and can never exceed it.
        Money cappedPaid = combined.compareTo(serverTotal) > 0 ? serverTotal : combined;

        if (cashPaid != null) {
            // cash_tendered ACCUMULATES rather than being overwritten: two Rs 350
            // payments on a Rs 700 order must leave the receipt reading "Cash 700 /
            // Change 0", not "Cash 350" sitting under a "TOTAL 700" line (which is what
            // overwriting produced — a receipt that looks underpaid on every reprint).
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE customer_order
                    SET amount_paid = ?, cash_tendered = COALESCE(cash_tendered, 0) + ?
                    WHERE id = ?
                    """)) {
                ps.setBigDecimal(1, cappedPaid.asBigDecimal());
                ps.setBigDecimal(2, cashPaid.asBigDecimal());
                ps.setLong(3, orderId);
                ps.executeUpdate();
            }
        } else if (!cappedPaid.equals(alreadyPaid)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE customer_order SET amount_paid = ? WHERE id = ?")) {
                ps.setBigDecimal(1, cappedPaid.asBigDecimal());
                ps.setLong(2, orderId);
                ps.executeUpdate();
            }
        }
        alreadyPaid = cappedPaid;

        // No "total > 0" guard here: a legitimately free order (a 100% discount clamps
        // discount_total to subtotal, making total exactly 0) has nothing left to
        // collect and IS settled. Requiring total > 0 stranded such orders in PENDING
        // forever — no payment could ever satisfy them, since amount_paid is capped at
        // the total, so they never counted as revenue and never left the Pending pane.
        // rollupTotals always runs before this, so `total` is authoritative by now.
        OrderStatus resolved;
        if (alreadyPaid.compareTo(serverTotal) >= 0) {
            resolved = OrderStatus.PAYMENT_RECEIVED;
        } else if (alreadyPaid.isPositive()) {
            resolved = OrderStatus.PARTIALLY_PAID;
        } else {
            resolved = OrderStatus.PENDING;
        }

        if (fromStatus == null || fromStatus != resolved) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE customer_order
                    SET status = ?, completed_at = CASE WHEN ? THEN now() ELSE completed_at END
                    WHERE id = ?
                    """)) {
                ps.setString(1, resolved.name());
                ps.setBoolean(2, resolved == OrderStatus.PAYMENT_RECEIVED);
                ps.setLong(3, orderId);
                ps.executeUpdate();
            }
            recordStatusChange(conn, orderId, fromStatus, resolved, staffId);
        }
        return resolved;
    }

    /**
     * Records an additional cash payment against a still-Pending order from the
     * Dashboard, moving it to PARTIALLY_PAID or, once amount_paid reaches the total,
     * PAYMENT_RECEIVED. Optimistic on the caller's last-seen status, same shape as
     * updateStatus: returns false if the order is no longer awaiting payment (cancelled
     * or already settled by another terminal) rather than silently applying a payment
     * to the wrong state. A part-paid order accepts further payments, so the guard is
     * awaitsPayment(), not an equality check against PENDING.
     */
    public boolean recordPayment(long orderId, Money amount, Integer staffId) throws DatabaseException {
        if (amount == null || amount.compareTo(Money.ZERO) <= 0) {
            throw new DatabaseException("Enter an amount greater than zero.");
        }
        return db.inTransaction(conn -> {
            OrderStatus current;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM customer_order WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new DatabaseException("This order no longer exists.");
                    current = OrderStatus.valueOf(rs.getString(1));
                    if (!current.awaitsPayment()) {
                        return false;
                    }
                }
            }
            applyPayment(conn, orderId, amount, staffId, current);
            return true;
        });
    }

    private void recordStatusChange(Connection conn, long orderId, OrderStatus from, OrderStatus to, Integer staffId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO order_status_history (order_id, from_status, to_status, staff_id)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setLong(1, orderId);
            if (from == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, from.name());
            ps.setString(3, to.name());
            if (staffId == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, staffId);
            ps.executeUpdate();
        }
    }

    /**
     * Advances an order's status with optimistic concurrency: the update only applies if
     * the order is still in the status the caller last saw. Returns false if another
     * terminal already changed it (caller should refresh and inform the user). Used for
     * cancellation from the dashboard — PENDING and PAYMENT_RECEIVED can both be
     * cancelled (OrderStatus.canCancel()).
     */
    public boolean updateStatus(long orderId, OrderStatus expectedCurrent, OrderStatus newStatus, Integer staffId)
            throws DatabaseException {
        // State-machine guard. The optimistic WHERE clause below only checks that the row
        // still holds `expectedCurrent`; it does not check that the move is legal, so a
        // caller passing CANCELLED -> PAYMENT_RECEIVED was accepted outright (reproduced
        // as TEST035). Resurrecting a cancelled order would silently put its total back
        // into revenue, so the rule is enforced here rather than trusted to callers.
        if (!expectedCurrent.canTransitionTo(newStatus)) {
            throw new DatabaseException("Cannot change an order from "
                + expectedCurrent.label() + " to " + newStatus.label() + ".");
        }
        return db.inTransaction(conn -> {
            boolean isCompleted = newStatus == OrderStatus.PAYMENT_RECEIVED;
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE customer_order
                    SET status = ?, completed_at = CASE WHEN ? THEN now() ELSE completed_at END
                    WHERE id = ? AND status = ?
                    """)) {
                ps.setString(1, newStatus.name());
                ps.setBoolean(2, isCompleted);
                ps.setLong(3, orderId);
                ps.setString(4, expectedCurrent.name());
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    return false;
                }
            }
            recordStatusChange(conn, orderId, expectedCurrent, newStatus, staffId);
            return true;
        });
    }

    // ---------------------------------------------------------------- reads

    private static final String ORDER_COLUMNS = """
        id, order_number, business_date, order_type, status, staff_id, staff_name,
        created_at, updated_at, completed_at, subtotal, discount_total, tax_total,
        delivery_fee, total, discount_mode, discount_rate, cash_tendered, amount_paid,
        table_number, customer_name, customer_phone, delivery_address, notes
        """;

    public Order loadOrderForPrint(long orderId) throws DatabaseException {
        return db.inReadOnly(conn -> loadOrderForPrint(conn, orderId));
    }

    private Order loadOrderForPrint(Connection conn, long orderId) throws SQLException {
        Order header;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + ORDER_COLUMNS + " FROM customer_order WHERE id = ?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                header = mapOrder(rs, List.of());
            }
        }
        List<OrderLine> lines = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, order_id, line_no, menu_item_id, variant_id, item_name, size_label,
                       unit_price, quantity, line_total, notes, option_value_id, option_group_name, option_value_name
                FROM order_line WHERE order_id = ? ORDER BY line_no
                """)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(mapLine(rs));
                }
            }
        }
        return header.withLines(lines);
    }

    public record OrderFilter(OrderType type, OrderStatus status, LocalDate from, LocalDate to) {
        public static OrderFilter todayAllTypes() {
            LocalDate today = businessDate(ZonedDateTime.now());
            return new OrderFilter(null, null, today, today);
        }
    }

    public record OrderRow(Order order, int itemCount) {}

    private static final String ORDER_COLUMNS_PREFIXED = """
        co.id, co.order_number, co.business_date, co.order_type, co.status, co.staff_id, co.staff_name,
        co.created_at, co.updated_at, co.completed_at, co.subtotal, co.discount_total, co.tax_total,
        co.delivery_fee, co.total, co.discount_mode, co.discount_rate, co.cash_tendered, co.amount_paid,
        co.table_number, co.customer_name, co.customer_phone, co.delivery_address, co.notes
        """;

    /** For the dashboard's live list. Reads on the read-only connection. */
    public List<OrderRow> loadOrders(OrderFilter filter) throws DatabaseException {
        StringBuilder sql = new StringBuilder(
            "SELECT " + ORDER_COLUMNS_PREFIXED + ", COALESCE(lc.n, 0) AS item_count "
            + "FROM customer_order co "
            + "LEFT JOIN (SELECT order_id, COUNT(*) AS n FROM order_line GROUP BY order_id) lc "
            + "ON lc.order_id = co.id WHERE co.business_date BETWEEN ? AND ?");
        List<Object> params = new ArrayList<>();
        params.add(filter.from());
        params.add(filter.to());
        if (filter.type() != null) {
            sql.append(" AND co.order_type = ?");
            params.add(filter.type().name());
        }
        if (filter.status() != null) {
            sql.append(" AND co.status = ?");
            params.add(filter.status().name());
        }
        sql.append(" ORDER BY co.created_at DESC");

        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<OrderRow> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(new OrderRow(mapOrder(rs, List.of()), rs.getInt("item_count")));
                    }
                    return result;
                }
            }
        });
    }

    /** Cheap change-detection for the dashboard poller: count + latest update timestamp. */
    public String fingerprint(OrderFilter filter) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT count(*)::text || ':' || COALESCE(max(updated_at)::text, '-')
                    FROM customer_order
                    WHERE business_date BETWEEN ? AND ?
                      AND (? IS NULL OR order_type = ?)
                      AND (? IS NULL OR status = ?)
                    """)) {
                ps.setObject(1, filter.from());
                ps.setObject(2, filter.to());
                String type = filter.type() == null ? null : filter.type().name();
                ps.setString(3, type);
                ps.setString(4, type);
                String status = filter.status() == null ? null : filter.status().name();
                ps.setString(5, status);
                ps.setString(6, status);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    /** revenue = what customers actually paid (post-discount, post-delivery-fee — the
     *  same `total` column the receipt and the order record itself treat as truth) on
     *  orders that have actually settled, not the pre-discount subtotal and not money
     *  still outstanding on a Pending order. outstanding = the flip side: money on
     *  Pending orders not yet collected (total - amount_paid), never Cancelled — a
     *  cancelled order's uncollected balance was never going to be collected and
     *  inflating "money still owed" with it would be misleading. */
    public record DailySummary(long orderCount, Money revenue, Money outstanding) {}

    /**
     * Counts exactly the rows the Dashboard's own type/status/date filter matches — the
     * same WHERE clause as loadOrders and fingerprint — so the order count always agrees
     * with the table beneath it.
     *
     * <p>Revenue and outstanding, however, are additionally restricted by status
     * (PAYMENT_RECEIVED / anything still awaiting payment respectively) regardless of
     * the dropdown's own status filter. Note outstanding sums total - amount_paid, so a
     * PARTIALLY_PAID order contributes only the balance still owed, not its whole
     * total. Cancelled is excluded from both because it is money never collected and never
     * will be — summing it into either card overstates the day's actual takings or its
     * actual exposure (verified: one sample day read Rs 52,333 against Rs 37,284
     * actually collected, back when the only excluded status was Cancelled). Filtering
     * the dashboard TO Cancelled therefore shows that status's order count with both
     * cards at Rs 0.00, which is the truthful figure, and keeps this screen reconciling
     * with the Reports tab, which is PAYMENT_RECEIVED-only.
     */
    public DailySummary dailySummary(OrderFilter filter) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT
                        count(*) AS order_count,
                        COALESCE(SUM(total) FILTER (WHERE status = 'PAYMENT_RECEIVED'), 0) AS revenue,
                        COALESCE(SUM(total - amount_paid)
                            FILTER (WHERE status IN ('PENDING','PARTIALLY_PAID')), 0) AS outstanding
                    FROM customer_order
                    WHERE business_date BETWEEN ? AND ?
                      AND (? IS NULL OR order_type = ?)
                      AND (? IS NULL OR status = ?)
                    """)) {
                ps.setObject(1, filter.from());
                ps.setObject(2, filter.to());
                String type = filter.type() == null ? null : filter.type().name();
                ps.setString(3, type);
                ps.setString(4, type);
                String status = filter.status() == null ? null : filter.status().name();
                ps.setString(5, status);
                ps.setString(6, status);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new DailySummary(rs.getLong("order_count"),
                        Money.of(rs.getBigDecimal("revenue")),
                        Money.of(rs.getBigDecimal("outstanding")));
                }
            }
        });
    }

    private Order mapOrder(ResultSet rs, List<OrderLine> lines) throws SQLException {
        Money total = Money.of(rs.getBigDecimal("total"));
        Money amountPaid = Money.of(rs.getBigDecimal("amount_paid"));
        OrderTotals totals = new OrderTotals(
            Money.of(rs.getBigDecimal("subtotal")),
            Money.of(rs.getBigDecimal("discount_total")),
            Money.of(rs.getBigDecimal("tax_total")),
            Money.of(rs.getBigDecimal("delivery_fee")),
            total,
            rs.getBigDecimal("cash_tendered") == null ? null : Money.of(rs.getBigDecimal("cash_tendered")),
            amountPaid
        );
        return new Order(
            rs.getLong("id"),
            rs.getString("order_number"),
            rs.getObject("business_date", LocalDate.class),
            OrderType.valueOf(rs.getString("order_type")),
            OrderStatus.valueOf(rs.getString("status")),
            (Integer) rs.getObject("staff_id"),
            rs.getString("staff_name"),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("updated_at")),
            toOffsetDateTime(rs.getTimestamp("completed_at")),
            totals,
            DiscountMode.valueOf(rs.getString("discount_mode")),
            rs.getBigDecimal("discount_rate"),
            rs.getString("table_number"),
            rs.getString("customer_name"),
            rs.getString("customer_phone"),
            rs.getString("delivery_address"),
            rs.getString("notes"),
            lines
        );
    }

    private OrderLine mapLine(ResultSet rs) throws SQLException {
        return new OrderLine(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getInt("line_no"),
            (Integer) rs.getObject("menu_item_id"),
            (Integer) rs.getObject("variant_id"),
            rs.getString("item_name"),
            rs.getString("size_label"),
            Money.of(rs.getBigDecimal("unit_price")),
            rs.getInt("quantity"),
            Money.of(rs.getBigDecimal("line_total")),
            rs.getString("notes"),
            (Integer) rs.getObject("option_value_id"),
            rs.getString("option_group_name"),
            rs.getString("option_value_name")
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atZone(ZonedDateTime.now().getZone()).toOffsetDateTime();
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static String customerInfoErrorMessage(OrderDraft draft) {
        if (draft.type() == OrderType.DINE_IN && rps.util.Validators.isBlank(draft.tableNumber())) {
            return "A table number is required for dine-in orders.";
        }
        return "A valid phone number is required"
            + (draft.type() == OrderType.DELIVERY ? ", and delivery needs a valid address." : ".");
    }
}
