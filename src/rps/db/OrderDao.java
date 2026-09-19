package rps.db;

import rps.model.DiscountMode;
import rps.model.DraftLine;
import rps.model.Order;
import rps.model.OrderDraft;
import rps.model.OrderLine;
import rps.model.FulfilmentStatus;
import rps.model.PaymentStatus;
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
     *  as a paid one — editing it may raise or lower its total and therefore its payment
     *  status, which updateOrder re-derives after saving. Time-based, so it can only
     *  be enforced here and in updateOrder itself — Postgres CHECK constraints reject
     *  non-immutable functions like now(), so this can't also live as a DB constraint the
     *  way phone-required or the discount clamp do. */
    public static boolean canEdit(Order order) {
        if (order.fulfilmentStatus().isCancelled()) return false;
        int windowMinutes = rps.util.AppSettings.get().orderEditWindowMinutes();
        OffsetDateTime deadline = order.createdAt().plusMinutes(windowMinutes);
        // OffsetDateTime.isBefore compares by instant, not local wall-clock, so the two
        // offsets don't need to match for this to be correct.
        return OffsetDateTime.now().isBefore(deadline);
    }

    /**
     * Whether more items can still be ADDED to an order — which is a different question
     * from whether it can be {@link #canEdit edited}, and deliberately outlives it.
     *
     * <p>The edit window exists to stop what has already been sent to the kitchen and
     * charged for being rewritten after the fact. It was never meant to stop a customer
     * ordering a second round: a table that ate an hour ago and wants more food is placing
     * an addition, not retroactively altering history. So once the window closes the
     * existing lines freeze, but the order stays open to additions for as long as it is
     * live — only cancellation closes it for good.
     */
    public static boolean canAddItems(Order order) {
        return order.fulfilmentStatus().isLive();
    }

    private record PricedVariant(int variantId, int menuItemId, String itemName, String sizeLabel, Money price) {}
    private record PricedOption(int optionValueId, int groupId, String groupName, String valueName) {}

    /**
     * Saves an order and all of its lines atomically: prices are re-read server-side,
     * the order number comes from the daily counter, lines are batch-inserted, and the
     * stored totals (including discount) are rolled up from the rows actually written
     * and the discount mode/value in ONE SQL statement — never summed or discounted in
     * Java — so the exact-equality CHECK on customer_order can never be violated by a
     * Java/SQL rounding disagreement. The order is saved UNPAID and PENDING; a full (or
     * overpaid) cash amount immediately moves the payment track to PAID, a short or absent
     * amount leaves a balance for a later rps.db.OrderDao#recordPayment. Fulfilment always
     * starts PENDING — cash arriving with the order says nothing about whether the kitchen
     * has made it. Returns the order re-read from the database, which both receipts render.
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
                        (order_number, business_date, order_type, payment_status, staff_id, staff_name,
                         table_number, customer_name, customer_phone, delivery_address, notes, completed_at,
                         discount_mode, discount_rate)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?, NULL, ?, ?)
                    RETURNING id
                    """)) {
                ps.setString(1, orderNumber);
                ps.setObject(2, bizDate);
                ps.setString(3, draft.type().name());
                // fulfilment_status is left to its column default of PENDING — a brand-new
                // order is always still being prepared, whatever cash arrives with it.
                ps.setString(4, PaymentStatus.UNPAID.name());
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

            insertLines(conn, orderId, draft.lines(), priced, pricedOptions, 0);
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

            insertLines(conn, orderId, draft.lines(), priced, pricedOptions, 0);
            rollupTotals(conn, orderId, draft.type(), mode, discountValue);

            applyPayment(conn, orderId, cashPaid, staffId, current.paymentStatus());

            return loadOrderForPrint(conn, orderId);
        });
    }

    /**
     * Appends items to an order whose existing lines are frozen — the "second round" path
     * that stays open after the edit window has closed (see {@link #canAddItems}).
     *
     * <p>Deliberately not expressed as updateOrder with a longer line list: updateOrder
     * deletes every line and rewrites them, which is exactly the operation the closed edit
     * window is meant to prevent. This only ever INSERTs, so nothing already sent to the
     * kitchen or already charged for can be altered or removed by this path, whatever the
     * caller passes.
     *
     * <p>Totals are re-rolled by the same rollupTotals used everywhere else, and the payment
     * status re-derived against the new, higher total: adding to a fully-paid order
     * correctly moves it back to Partially Paid with a balance owing. The order's type,
     * contact details and discount are untouched — only lines and the money that follows
     * from them. An AMOUNT discount is re-applied from the stored discount_total (the raw
     * figure the cashier originally typed was never persisted), matching how
     * OrderEditDialog reopens one.
     */
    public Order addItemsToOrder(long orderId, List<DraftLine> newLines, Money additionalCash,
                                  int staffId, String staffName) throws DatabaseException {
        final List<DraftLine> lines = List.copyOf(newLines);   // same isolation reasoning as saveOrder
        if (lines.isEmpty()) {
            throw new DatabaseException("Add at least one item.");
        }
        return db.inTransaction(conn -> {
            Order current = loadOrderForPrint(conn, orderId);
            if (current == null) {
                throw new DatabaseException("This order no longer exists.");
            }
            if (!canAddItems(current)) {
                throw new DatabaseException("This order was cancelled — nothing more can be added to it.");
            }

            List<Integer> variantIds = lines.stream().map(DraftLine::variantId).toList();
            Map<Integer, PricedVariant> priced = fetchOrderableVariants(conn, variantIds);
            for (Integer vid : variantIds) {
                if (!priced.containsKey(vid)) {
                    throw new DatabaseException("An item being added is no longer available.");
                }
            }
            List<Integer> optionIds = lines.stream().map(DraftLine::optionValueId)
                .filter(java.util.Objects::nonNull).toList();
            Map<Integer, PricedOption> pricedOptions = fetchOrderableOptions(conn, optionIds);
            validateLineOptions(conn, lines, priced, pricedOptions);

            int maxLineNo;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(MAX(line_no), 0) FROM order_line WHERE order_id = ?")) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    maxLineNo = rs.getInt(1);
                }
            }
            insertLines(conn, orderId, lines, priced, pricedOptions, maxLineNo);

            BigDecimal discountValue = switch (current.discountMode()) {
                case PERCENT -> current.discountRate();
                case AMOUNT -> current.totals().discountTotal().asBigDecimal();
                case NONE -> null;
            };
            rollupTotals(conn, orderId, current.type(), current.discountMode(), discountValue);
            applyPayment(conn, orderId, additionalCash, staffId, current.paymentStatus());

            return loadOrderForPrint(conn, orderId);
        });
    }

    /** {@code startLineNo} is 0 when writing an order's lines from scratch; appending to an
     *  existing order passes the highest line_no already stored, so added lines continue
     *  the numbering instead of colliding with what is already there. */
    private void insertLines(Connection conn, long orderId, List<DraftLine> lines,
                              Map<Integer, PricedVariant> priced, Map<Integer, PricedOption> pricedOptions,
                              int startLineNo)
            throws SQLException, DatabaseException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO order_line
                    (order_id, line_no, menu_item_id, variant_id, item_name, size_label, unit_price, quantity,
                     notes, option_value_id, option_group_name, option_value_name)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            int lineNo = startLineNo;
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
     * payments, and derives the resulting payment status: PAID once amount_paid reaches
     * total, PARTIALLY_PAID while some is in, otherwise UNPAID. The fulfilment track is
     * never touched here. {@code fromStatus} is null for a brand-new order
     * (no prior status to transition from in the history log); for an edit it's the
     * order's status before this save, so the transition is recorded even when the
     * amount entered is null (e.g. the total changed enough on its own to cross the
     * paid/pending line).
     *
     */
    private PaymentStatus applyPayment(Connection conn, long orderId, Money cashPaid, Integer staffId,
                              PaymentStatus fromStatus) throws SQLException {
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
        PaymentStatus resolved;
        if (alreadyPaid.compareTo(serverTotal) >= 0) {
            resolved = PaymentStatus.PAID;
        } else if (alreadyPaid.isPositive()) {
            resolved = PaymentStatus.PARTIALLY_PAID;
        } else {
            resolved = PaymentStatus.UNPAID;
        }

        if (fromStatus == null || fromStatus != resolved) {
            // completed_at is no longer touched here. It marks when the order was handed
            // to the customer, which is a fulfilment event — money arriving says nothing
            // about whether the food has gone out, and conflating the two is exactly what
            // this split exists to undo. setFulfilment stamps it instead.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE customer_order SET payment_status = ? WHERE id = ?")) {
                ps.setString(1, resolved.name());
                ps.setLong(2, orderId);
                ps.executeUpdate();
            }
            recordStatusChange(conn, orderId, StatusKind.PAYMENT,
                fromStatus == null ? null : fromStatus.name(), resolved.name(), staffId);
        }

        // A debt collected in full stops being a debt: it leaves the Pay Later ledger here
        // and reappears on the Dashboard as an ordinary settled order. Done in applyPayment
        // rather than in recordPayment alone so EVERY route to "fully paid" settles it —
        // there must be no way to end up with a paid order still sitting on the ledger.
        //
        // Revenue does not move as a result: the total was already recognised when credit
        // was given, and dailySummary's (payment_status = 'PAID' OR loan_at IS NOT NULL)
        // matches this row exactly once both before and after this statement.
        if (resolved == PaymentStatus.PAID) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE customer_order SET loan_settled_at = now() WHERE id = ? AND "
                    + ON_LEDGER)) {
                ps.setLong(1, orderId);
                ps.executeUpdate();
            }
        }
        return resolved;
    }

    /**
     * Records an additional cash payment against a still-Pending order from the
     * Dashboard, moving it to PARTIALLY_PAID or, once amount_paid reaches the total,
     * PAID. Two different refusals on purpose: an order that is merely already settled
     * returns false (benign — another terminal got there first, just refresh), while a
     * CANCELLED order throws, because taking cash against a voided order is a mistake the
     * cashier must be told about — that money would never reach the books, cancelled
     * orders being excluded from revenue. Optimistic on the caller's last-seen status,
     * same shape as
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
            PaymentStatus current;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT payment_status, fulfilment_status FROM customer_order WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new DatabaseException("This order no longer exists.");
                    current = PaymentStatus.valueOf(rs.getString(1));
                    // A cancelled order collects nothing more, whatever its payment row
                    // says — money taken against one would never appear in the takings,
                    // since revenue excludes cancelled orders entirely.
                    if (FulfilmentStatus.valueOf(rs.getString(2)).isCancelled()) {
                        throw new DatabaseException("This order was cancelled — no further payment can be recorded.");
                    }
                    if (!current.awaitsPayment()) {
                        return false;
                    }
                }
            }
            applyPayment(conn, orderId, amount, staffId, current);
            return true;
        });
    }

    /** Which of the two independent tracks a history row describes. */
    private enum StatusKind { PAYMENT, FULFILMENT }

    private void recordStatusChange(Connection conn, long orderId, StatusKind kind,
                                     String from, String to, Integer staffId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO order_status_history (order_id, status_kind, from_status, to_status, staff_id)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, orderId);
            ps.setString(2, kind.name());
            if (from == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, from);
            ps.setString(4, to);
            if (staffId == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, staffId);
            ps.executeUpdate();
        }
    }

    /**
     * How long after it was placed an order can still be cancelled without the Force
     * Cancel override. Separate setting from the edit window — see
     * AppSettings#orderCancelWindowMinutes. Applies regardless of whether the customer has
     * paid; paying does not buy more time to change your mind, and not paying does not
     * leave the order voidable forever.
     */
    public static boolean withinCancelWindow(Order order) {
        int minutes = rps.util.AppSettings.get().orderCancelWindowMinutes();
        return OffsetDateTime.now().isBefore(order.createdAt().plusMinutes(minutes));
    }

    /** Whether the plain Cancel action applies: a live order still inside its window. */
    public static boolean canCancel(Order order) {
        return order.fulfilmentStatus().isLive() && withinCancelWindow(order);
    }

    /**
     * Moves the fulfilment track — the kitchen/counter state — with optimistic
     * concurrency: the update only applies if the order is still in the state the caller
     * last saw, so a second terminal that already changed it returns false rather than
     * silently overwriting. Payment is untouched; cancelling an order that was paid leaves
     * it visibly paid, and it simply stops counting toward revenue.
     *
     * <p>{@code force} bypasses only the time window, never the state machine: a cancelled
     * order stays cancelled, and COMPLETED still cannot walk back to PENDING. Every forced
     * cancellation is written to order_status_history against the staff member who did it,
     * which is the whole point of having the override be explicit rather than just
     * widening the window.
     */
    public boolean updateFulfilment(long orderId, FulfilmentStatus expectedCurrent,
                                     FulfilmentStatus newStatus, Integer staffId, boolean force)
            throws DatabaseException {
        if (!expectedCurrent.canTransitionTo(newStatus)) {
            throw new DatabaseException("Cannot change an order from "
                + expectedCurrent.label() + " to " + newStatus.label() + ".");
        }
        return db.inTransaction(conn -> {
            if (newStatus == FulfilmentStatus.CANCELLED) {
                return cancelOrder(conn, orderId, expectedCurrent, force);
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE customer_order
                    SET fulfilment_status = ?,
                        completed_at = CASE WHEN ? THEN now() ELSE completed_at END
                    WHERE id = ? AND fulfilment_status = ?
                    """)) {
                ps.setString(1, newStatus.name());
                ps.setBoolean(2, newStatus == FulfilmentStatus.COMPLETED);
                ps.setLong(3, orderId);
                ps.setString(4, expectedCurrent.name());
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            recordStatusChange(conn, orderId, StatusKind.FULFILMENT,
                expectedCurrent.name(), newStatus.name(), staffId);
            return true;
        });
    }

    /**
     * Cancelling an order deletes it outright rather than marking it CANCELLED and
     * keeping the row — a deliberate choice: a cancelled order should leave no trace
     * anywhere in the system (not in Total Orders, not in Reports, not in the Pay Later
     * ledger if it happened to be on account), rather than persisting as a voided record.
     *
     * <p>The tradeoff, made knowingly: there is no audit trail of a cancellation
     * surviving this call, and the next order still takes the next sequential number, so
     * a cancelled order leaves a gap (e.g. #005 then #007) with nothing in the system to
     * explain it. Optimistic concurrency is preserved the same way the UPDATE path in
     * {@link #updateFulfilment} checks it: the DELETE only matches if the order is still
     * in the exact fulfilment state the caller last saw.
     */
    private boolean cancelOrder(Connection conn, long orderId, FulfilmentStatus expectedCurrent, boolean force)
            throws SQLException, DatabaseException {
        // Re-checked inside the transaction rather than trusted from the caller: the
        // dashboard decides which buttons to show minutes before anyone clicks one.
        if (!force) {
            Order current = loadOrderForPrint(conn, orderId);
            // Already gone -- most likely a concurrent cancel (another terminal, or a
            // double-click) beat this call to it. Same "someone else already changed it,
            // refresh and try again" shape as every other optimistic-concurrency check in
            // this class, not a hard error: the caller's own goal — this order being
            // gone — is already achieved either way.
            if (current == null) return false;
            if (!withinCancelWindow(current)) {
                throw new DatabaseException("This order can no longer be cancelled — the "
                    + rps.util.AppSettings.get().orderCancelWindowMinutes()
                    + "-minute cancellation window has passed. Use Force Cancel if it really must be voided.");
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM order_status_history WHERE order_id = ?")) {
            ps.setLong(1, orderId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM order_line WHERE order_id = ?")) {
            ps.setLong(1, orderId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM customer_order WHERE id = ? AND fulfilment_status = ?")) {
            ps.setLong(1, orderId);
            ps.setString(2, expectedCurrent.name());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Wipes every order and delivery record while leaving staff accounts and the menu
     * completely untouched — the triple-confirmed "Reset All Data" action in Settings,
     * for a manager who wants the till's books to start over (a demo period ending, a
     * till changing hands) without reinstalling anything. TRUNCATE, not DELETE, so the
     * daily order-sequence and every id resets too and the next order genuinely numbers
     * -001 again — the same operation reset-for-handover.ps1 already performs from
     * outside the app before a client handover; this exposes it from inside the running
     * app for a manager to use on their own machine afterwards. RESTART IDENTITY CASCADE
     * requires no FK-order juggling: category/menu_item/staff are never touched, so
     * nothing here can cascade into them.
     */
    public void resetAllTradingData() throws DatabaseException {
        db.inTransaction(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    TRUNCATE TABLE
                        order_status_history, order_line, customer_order, daily_counter
                    RESTART IDENTITY CASCADE
                    """);
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- reads

    /**
     * What it means for an order to be a live debt on the Pay Later ledger, written once so
     * the Dashboard and the ledger can never disagree about which of them owns a row.
     *
     * <p>Collected in full, an order settles: it leaves the ledger and goes back to the
     * Dashboard. {@link #ON_LEDGER} and {@link #OFF_LEDGER} are exact complements, so every
     * order is always in exactly one of the two screens — never both, never neither.
     */
    private static final String ON_LEDGER = "(loan_at IS NOT NULL AND loan_settled_at IS NULL)";
    private static final String OFF_LEDGER = "(loan_at IS NULL OR loan_settled_at IS NOT NULL)";
    private static final String ON_LEDGER_CO = "(co.loan_at IS NOT NULL AND co.loan_settled_at IS NULL)";
    private static final String OFF_LEDGER_CO = "(co.loan_at IS NULL OR co.loan_settled_at IS NOT NULL)";

    private static final String ORDER_COLUMNS = """
        id, order_number, business_date, order_type, payment_status, fulfilment_status, staff_id, staff_name,
        created_at, updated_at, completed_at, subtotal, discount_total, tax_total,
        delivery_fee, total, discount_mode, discount_rate, cash_tendered, amount_paid,
        table_number, customer_name, customer_phone, delivery_address, notes, loan_at, loan_settled_at
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

    /** Either status may be null, meaning "any" — the dashboard filters the two tracks
     *  independently, e.g. "still being prepared" crossed with "already paid". */
    public record OrderFilter(OrderType type, PaymentStatus paymentStatus,
                              FulfilmentStatus fulfilmentStatus, LocalDate from, LocalDate to) {
        public static OrderFilter todayAllTypes() {
            LocalDate today = businessDate(ZonedDateTime.now());
            return new OrderFilter(null, null, null, today, today);
        }
    }

    public record OrderRow(Order order, int itemCount) {}

    private static final String ORDER_COLUMNS_PREFIXED = """
        co.id, co.order_number, co.business_date, co.order_type, co.payment_status, co.fulfilment_status, co.staff_id, co.staff_name,
        co.created_at, co.updated_at, co.completed_at, co.subtotal, co.discount_total, co.tax_total,
        co.delivery_fee, co.total, co.discount_mode, co.discount_rate, co.cash_tendered, co.amount_paid,
        co.table_number, co.customer_name, co.customer_phone, co.delivery_address, co.notes,
        co.loan_at, co.loan_settled_at
        """;

    /** For the dashboard's live list. Reads on the read-only connection. */
    public List<OrderRow> loadOrders(OrderFilter filter) throws DatabaseException {
        StringBuilder sql = new StringBuilder(
            "SELECT " + ORDER_COLUMNS_PREFIXED + ", COALESCE(lc.n, 0) AS item_count "
            + "FROM customer_order co "
            + "LEFT JOIN (SELECT order_id, COUNT(*) AS n FROM order_line GROUP BY order_id) lc "
            // Moving an order to "pay later" moves it OFF this screen entirely — it is no
            // longer something the counter settles, it is a debt tracked in the Pay Later
            // ledger (loadLoanOrders). Collected in full it settles and comes straight back
            // here as an ordinary paid order, which is what OFF_LEDGER's second half allows.
            + "ON lc.order_id = co.id WHERE " + OFF_LEDGER_CO + " AND co.business_date BETWEEN ? AND ?");
        List<Object> params = new ArrayList<>();
        params.add(filter.from());
        params.add(filter.to());
        if (filter.type() != null) {
            sql.append(" AND co.order_type = ?");
            params.add(filter.type().name());
        }
        if (filter.paymentStatus() != null) {
            sql.append(" AND co.payment_status = ?");
            params.add(filter.paymentStatus().name());
        }
        if (filter.fulfilmentStatus() != null) {
            sql.append(" AND co.fulfilment_status = ?");
            params.add(filter.fulfilmentStatus().name());
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
                    WHERE """ + OFF_LEDGER + """
                      AND business_date BETWEEN ? AND ?
                      AND (? IS NULL OR order_type = ?)
                      AND (? IS NULL OR payment_status = ?)
                      AND (? IS NULL OR fulfilment_status = ?)
                    """)) {
                ps.setObject(1, filter.from());
                ps.setObject(2, filter.to());
                String type = filter.type() == null ? null : filter.type().name();
                ps.setString(3, type);
                ps.setString(4, type);
                String pay = filter.paymentStatus() == null ? null : filter.paymentStatus().name();
                ps.setString(5, pay);
                ps.setString(6, pay);
                String ful = filter.fulfilmentStatus() == null ? null : filter.fulfilmentStatus().name();
                ps.setString(7, ful);
                ps.setString(8, ful);
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
     * <p>Revenue and outstanding, however, are additionally restricted by payment state
     * (PAID / anything still awaiting payment respectively) regardless of the dropdown's
     * own filters. Note outstanding sums total - amount_paid, so a partly-paid order
     * contributes only the balance still owed, not its whole total.
     *
     * <p>A CANCELLED order is excluded from all three figures, including order_count — a
     * voided order was never really "an order" from the shop's point of view, and Total
     * Orders is meant to read as a count of real trade, not a count of rows in the table.
     * Cancelling means the money goes back too, so counting it toward revenue would
     * overstate the day's takings against what is actually in the till. Filtering the
     * dashboard TO Cancelled still shows those rows (and their own count) — this is what
     * decides the summary CARDS, not what the table itself can display.
     *
     * <p>A LOAN (pay-later) order counts as revenue the moment credit is given, even though
     * none of the cash has arrived, and stops counting as outstanding — the money owed is
     * tracked as a receivable by the Pay Later screen instead ({@link #loanSummary}). It is
     * also excluded from order_count, because it is no longer one of the rows listed
     * beneath these cards. The OR in the revenue filter cannot double-count a loan that is
     * later paid off in full: the row matches the filter once either way.
     */
    public DailySummary dailySummary(OrderFilter filter) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT
                        count(*) FILTER (WHERE (fulfilment_status <> 'CANCELLED' OR ? = 'CANCELLED')
                              AND """ + OFF_LEDGER + """
                        ) AS order_count,
                        COALESCE(SUM(total) FILTER (
                            WHERE (payment_status = 'PAID' OR loan_at IS NOT NULL)
                              AND fulfilment_status <> 'CANCELLED'), 0) AS revenue,
                        COALESCE(SUM(total - amount_paid) FILTER (
                            WHERE payment_status IN ('UNPAID','PARTIALLY_PAID')
                              AND fulfilment_status <> 'CANCELLED'
                              AND """ + OFF_LEDGER + """
                        ), 0) AS outstanding
                    FROM customer_order
                    WHERE business_date BETWEEN ? AND ?
                      AND (? IS NULL OR order_type = ?)
                      AND (? IS NULL OR payment_status = ?)
                      AND (? IS NULL OR fulfilment_status = ?)
                    """)) {
                String ful = filter.fulfilmentStatus() == null ? null : filter.fulfilmentStatus().name();
                // Bound twice: once here to decide whether order_count still excludes
                // CANCELLED rows (it does, UNLESS the dashboard is explicitly filtered to
                // Cancelled — then the count of "how many cancelled orders" is exactly
                // what that view is for, and showing 0 while the table below lists several
                // would look like a bug), and again further down as the ordinary WHERE
                // filter every other query on this page already applies.
                ps.setString(1, ful);
                ps.setObject(2, filter.from());
                ps.setObject(3, filter.to());
                String type = filter.type() == null ? null : filter.type().name();
                ps.setString(4, type);
                ps.setString(5, type);
                String pay = filter.paymentStatus() == null ? null : filter.paymentStatus().name();
                ps.setString(6, pay);
                ps.setString(7, pay);
                ps.setString(8, ful);
                ps.setString(9, ful);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new DailySummary(rs.getLong("order_count"),
                        Money.of(rs.getBigDecimal("revenue")),
                        Money.of(rs.getBigDecimal("outstanding")));
                }
            }
        });
    }

    // ---------------------------------------------------------------- pay later (loans)

    /**
     * Moves an order off the Dashboard and onto the Pay Later ledger: the customer is
     * leaving without settling, by agreement. From this moment the order's total counts as
     * revenue and stops counting as outstanding (see {@link #dailySummary}), while the
     * balance still owed becomes a receivable listed in the Pay Later screen.
     *
     * <p>Refuses rather than silently no-ops on the two states where credit makes no sense:
     * a cancelled order (there is no debt — also enforced by ck_loan_not_cancelled) and an
     * already fully-paid one (there is nothing to owe). Returns false if another terminal
     * already moved it, matching updateFulfilment/recordPayment's optimistic shape.
     */
    public boolean moveToLoan(long orderId, Integer staffId) throws DatabaseException {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT payment_status, fulfilment_status, loan_at, loan_settled_at
                    FROM customer_order WHERE id = ? FOR UPDATE
                    """)) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new DatabaseException("This order no longer exists.");
                    if (FulfilmentStatus.valueOf(rs.getString(2)).isCancelled()) {
                        throw new DatabaseException(
                            "This order was cancelled — there is nothing owed to move to Pay Later.");
                    }
                    if (!PaymentStatus.valueOf(rs.getString(1)).awaitsPayment()) {
                        throw new DatabaseException(
                            "This order is already paid in full — there is nothing to put on account.");
                    }
                    // Already an open debt. A PREVIOUSLY settled one is fine to put back on
                    // account (it can owe money again after items are added to it), which
                    // is why this tests the pair rather than loan_at alone.
                    if (rs.getTimestamp(3) != null && rs.getTimestamp(4) == null) return false;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE customer_order SET loan_at = now(), loan_settled_at = NULL, loan_staff_id = ? "
                    + "WHERE id = ? AND " + OFF_LEDGER)) {
                if (staffId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, staffId);
                ps.setLong(2, orderId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Undoes {@link #moveToLoan} — for an order put on account by mistake. The order
     *  returns to the Dashboard still owing what it owed; nothing about its payment or
     *  fulfilment state was ever changed by moving it. Clears the credit record entirely
     *  rather than marking it settled, because nothing was collected: this is an erasure of
     *  something that should never have been recorded, not the end of a debt. */
    public boolean returnFromLoan(long orderId) throws DatabaseException {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE customer_order SET loan_at = NULL, loan_settled_at = NULL, loan_staff_id = NULL "
                    + "WHERE id = ? AND " + ON_LEDGER)) {
                ps.setLong(1, orderId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * The Pay Later ledger: every debt still owed, and nothing else. An order collected in
     * full settles automatically (see {@link #applyPayment}) and leaves this list for the
     * Dashboard, so this screen only ever shows money still to come in.
     *
     * <p>Deliberately NOT restricted to a business-date range the way loadOrders is: a debt
     * from three weeks ago is exactly the thing this screen exists to stop anyone
     * forgetting, so it would be the worst possible row to hide behind a date filter
     * defaulting to today. Oldest first — the longest-unpaid debt needs chasing most.
     */
    public List<OrderRow> loadLoanOrders() throws DatabaseException {
        String sql = "SELECT " + ORDER_COLUMNS_PREFIXED + ", COALESCE(lc.n, 0) AS item_count "
            + "FROM customer_order co "
            + "LEFT JOIN (SELECT order_id, COUNT(*) AS n FROM order_line GROUP BY order_id) lc "
            + "ON lc.order_id = co.id WHERE " + ON_LEDGER_CO
            + " ORDER BY co.loan_at ASC";
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<OrderRow> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new OrderRow(mapOrder(rs, List.of()), rs.getInt("item_count")));
                }
                return result;
            }
        });
    }

    /**
     * Totals for the debts currently on the ledger — and only those. A loan collected in
     * full settles and leaves, taking its money out of every figure here: it is no longer
     * outstanding, and it is no longer part-collected either, it is simply a paid order on
     * the Dashboard. That is what stops a repaid debt showing up twice.
     *
     * <p>{@code receivedTotal} is therefore money recovered so far against debts that are
     * STILL open, and {@code outstandingAmount + receivedTotal} is the credit currently
     * riding on the ledger.
     */
    public record LoanSummary(long outstandingCount, Money outstandingAmount, Money receivedTotal) {}

    public LoanSummary loanSummary() throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT
                        count(*) AS n,
                        COALESCE(SUM(total - amount_paid), 0) AS owed,
                        COALESCE(SUM(amount_paid), 0) AS received
                    FROM customer_order
                    WHERE """ + ON_LEDGER + """
                      AND fulfilment_status <> 'CANCELLED'
                    """);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new LoanSummary(rs.getLong("n"),
                    Money.of(rs.getBigDecimal("owed")),
                    Money.of(rs.getBigDecimal("received")));
            }
        });
    }

    /**
     * Settles any loan that is already fully paid but somehow still on the ledger — the
     * self-healing counterpart to {@link #applyPayment}'s own settle step, for a row that
     * reached PAID some other way (a stale build, a direct DB edit, a bug not yet found).
     * Run opportunistically by the Pay Later screen before every refresh, so such a row
     * cannot sit there forever showing "Still Owed Rs 0.00" with no way to clear it — the
     * Collect action requires an amount greater than zero, which a paid-up order has none
     * of, so without this a stuck row would need manual SQL to fix.
     *
     * @return how many rows were fixed, purely for logging — callers do not need to branch on it.
     */
    public int reconcileLoanLedger() throws DatabaseException {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE customer_order SET loan_settled_at = now() WHERE "
                    + ON_LEDGER + " AND payment_status = 'PAID'")) {
                return ps.executeUpdate();
            }
        });
    }

    /** Cheap change-detection for the Pay Later poller, same idea as {@link #fingerprint}. */
    public String loanFingerprint() throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT count(*)::text || ':' || COALESCE(max(updated_at)::text, '-')
                    FROM customer_order WHERE """ + ON_LEDGER);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
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
            PaymentStatus.valueOf(rs.getString("payment_status")),
            FulfilmentStatus.valueOf(rs.getString("fulfilment_status")),
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
            toOffsetDateTime(rs.getTimestamp("loan_at")),
            toOffsetDateTime(rs.getTimestamp("loan_settled_at")),
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
        boolean phoneOptional = draft.type() == OrderType.DINE_IN || draft.isPhoneNotRequired();
        if (!phoneOptional && rps.util.Validators.isBlank(draft.customerPhone())) {
            return "A valid phone number is required"
                + (draft.type() == OrderType.DELIVERY ? ", and delivery needs a valid address." : ".");
        }
        if (draft.type() == OrderType.DELIVERY) {
            return "Delivery needs a valid address.";
        }
        return "The phone number entered isn't valid — check the format, or tick "
            + "\"No phone number\" if the customer won't give one.";
    }
}
