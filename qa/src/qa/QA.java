package qa;

import rps.db.Db;
import rps.db.DatabaseException;
import rps.model.DraftLine;
import rps.model.MenuItem;
import rps.model.MenuItemVariant;
import rps.model.Order;
import rps.model.OrderDraft;
import rps.model.OrderType;
import rps.util.Money;

import java.util.ArrayList;
import java.util.List;

/** Shared assertion + fixture helpers for the QA suite. */
public final class QA {

    public static int passed;
    public static int failed;
    private static final List<String> failures = new ArrayList<>();
    private static final List<Long> createdOrders = new ArrayList<>();

    private QA() {}

    public static void section(String name) {
        System.out.println("\n── " + name + " " + "─".repeat(Math.max(0, 66 - name.length())));
    }

    public static void check(String id, String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.printf("  PASS  %-9s %s%s%n", id, name, detail.isEmpty() ? "" : "  [" + detail + "]");
        } else {
            failed++;
            failures.add(id + " " + name + "  ->  " + detail);
            System.out.printf("  FAIL  %-9s %s  ->  %s%n", id, name, detail);
        }
    }

    /** Asserts an operation is REJECTED. Returns the rejection message, or null if it wrongly succeeded. */
    public static String expectReject(String id, String name, ThrowingRunnable r) {
        try {
            r.run();
            check(id, name, false, "operation was ACCEPTED but should have been rejected");
            return null;
        } catch (Exception e) {
            check(id, name, true, e.getMessage() == null ? e.getClass().getSimpleName()
                : truncate(e.getMessage(), 70));
            return String.valueOf(e.getMessage());
        }
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    public interface ThrowingRunnable { void run() throws Exception; }

    public static void summary(String suite) {
        System.out.println("\n" + "=".repeat(72));
        System.out.printf("  %s: %d passed, %d failed%n", suite, passed, failed);
        if (!failures.isEmpty()) {
            System.out.println("  Failures:");
            for (String f : failures) System.out.println("    - " + f);
        }
        System.out.println("=".repeat(72));
    }

    // ---------------------------------------------------------------- fixtures

    public static MenuItem anyItem(rps.db.MenuDao dao) throws DatabaseException {
        return dao.listAvailableItems().stream()
            .filter(m -> !m.variants().isEmpty()).findFirst().orElseThrow();
    }

    public static List<MenuItem> items(rps.db.MenuDao dao, int n) throws DatabaseException {
        return dao.listAvailableItems().stream().filter(m -> !m.variants().isEmpty()).limit(n).toList();
    }

    public static OrderDraft draft(OrderType type) {
        OrderDraft d = new OrderDraft();
        d.setType(type);
        if (type == OrderType.DINE_IN) {
            d.setTableNumber("7");
        } else {
            d.setCustomerPhone("03001234567");
        }
        if (type == OrderType.DELIVERY) {
            d.setDeliveryAddress("QA test address, near the test lab");
        }
        return d;
    }

    public static DraftLine line(MenuItem item, int qty) {
        MenuItemVariant v = item.variants().get(0);
        return new DraftLine(v.id(), item.name(), v.price(), qty, null, null, null, null);
    }

    public static DraftLine line(MenuItemVariant v, String name, int qty) {
        return new DraftLine(v.id(), name, v.price(), qty, null, null, null, null);
    }

    /** Records an order id so cleanup() can remove it afterwards. */
    public static Order track(Order o) {
        if (o != null) createdOrders.add(o.id());
        return o;
    }

    public static void trackId(long id) { createdOrders.add(id); }

    /** Removes every order this suite created, plus QA-created option groups. Rider/
     *  delivery-run cleanup was removed along with that feature (migration 23) — those
     *  tables no longer exist. */
    public static void cleanup(Db db) throws DatabaseException {
        for (long id : createdOrders) {
            db.inTransaction(conn -> {
                exec(conn, "DELETE FROM order_status_history WHERE order_id = " + id);
                exec(conn, "DELETE FROM order_line WHERE order_id = " + id);
                exec(conn, "DELETE FROM customer_order WHERE id = " + id);
                return null;
            });
        }
        db.inTransaction(conn -> {
            exec(conn, "DELETE FROM option_value WHERE option_group_id IN (SELECT id FROM option_group WHERE name LIKE 'QA %')");
            exec(conn, "UPDATE menu_item SET option_group_id = NULL "
                + "WHERE option_group_id IN (SELECT id FROM option_group WHERE name LIKE 'QA %')");
            exec(conn, "DELETE FROM option_group WHERE name LIKE 'QA %'");
            return null;
        });
        createdOrders.clear();
        System.out.println("\n  [cleanup] QA-created rows removed from rms_test");
    }

    private static void exec(java.sql.Connection conn, String sql) throws java.sql.SQLException {
        try (java.sql.Statement st = conn.createStatement()) { st.execute(sql); }
    }

    // ---------------------------------------------------------------- integrity

    /** The invariants that must hold after EVERY test run. Returns violations found. */
    public static int integritySweep(Db db, String label) throws DatabaseException {
        String[][] checks = {
            {"amount_paid exceeds total", "amount_paid > total"},
            {"PAID but underpaid", "payment_status='PAID' AND amount_paid < total"},
            {"UNPAID but has money", "payment_status='UNPAID' AND amount_paid > 0"},
            {"PARTIALLY_PAID out of range", "payment_status='PARTIALLY_PAID' AND (amount_paid <= 0 OR amount_paid >= total)"},
            // The two tracks are independent, so there is deliberately NO invariant
            // forbidding a cancelled order from holding money — that is a real state
            // (paid, then cancelled, awaiting refund). What must hold is that such an
            // order is excluded from revenue, which the reconciliation checks cover.
            {"unknown fulfilment state", "fulfilment_status NOT IN ('PENDING','COMPLETED','CANCELLED')"},
            {"total equation broken", "total <> subtotal - discount_total + tax_total + delivery_fee"},
            {"negative money", "subtotal < 0 OR total < 0 OR amount_paid < 0 OR discount_total < 0"},
            {"discount exceeds subtotal", "discount_total > subtotal"},
            // The 10-character floor was dropped from the app (Validators.isValidAddress,
            // Migrations#19) -- a short address like "Flat 3" is now legitimately valid,
            // so this check follows the same rule: blank is the only violation.
            {"delivery missing address", "order_type='DELIVERY' AND (delivery_address IS NULL OR length(btrim(delivery_address)) = 0)"},
            {"orphaned order_line", "id IN (SELECT order_id FROM order_line WHERE order_id NOT IN (SELECT id FROM customer_order))"},
        };
        int violations = 0;
        System.out.println("\n  Integrity sweep (" + label + "):");
        for (String[] c : checks) {
            long n = count(db, "SELECT count(*) FROM customer_order WHERE " + c[1]);
            if (n > 0) {
                violations += n;
                System.out.printf("    VIOLATION  %-34s %d row(s)%n", c[0], n);
            } else {
                System.out.printf("    ok         %-34s%n", c[0]);
            }
        }
        // Order-level: lines must sum to subtotal
        long badSubtotal = count(db, """
            SELECT count(*) FROM (
              SELECT co.id FROM customer_order co
              JOIN order_line ol ON ol.order_id = co.id
              GROUP BY co.id, co.subtotal
              HAVING co.subtotal <> SUM(ol.line_total)
            ) x""");
        if (badSubtotal > 0) {
            violations += badSubtotal;
            System.out.printf("    VIOLATION  %-34s %d row(s)%n", "subtotal != sum(line_total)", badSubtotal);
        } else {
            System.out.printf("    ok         %-34s%n", "subtotal = sum(line_total)");
        }
        long dupNumbers = count(db, "SELECT count(*) FROM (SELECT order_number FROM customer_order GROUP BY order_number HAVING count(*)>1) x");
        if (dupNumbers > 0) {
            violations += dupNumbers;
            System.out.printf("    VIOLATION  %-34s %d%n", "duplicate order_number", dupNumbers);
        } else {
            System.out.printf("    ok         %-34s%n", "order_number unique");
        }
        return violations;
    }

    public static long count(Db db, String sql) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (java.sql.Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    public static Money money(Db db, String sql) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (java.sql.Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return Money.of(rs.getBigDecimal(1));
            }
        });
    }
}
