package qa;

import rps.db.Db;
import rps.db.MenuDao;
import rps.db.OrderDao;
import rps.db.ReportsDao;
import rps.model.*;
import rps.util.Money;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static qa.QA.*;

/** §18 large data, §17 performance, §14 failure injection, §33 long-run stability. */
public final class StressSuite {

    public static void main(String[] args) throws Exception {
        Db db = Db.get();
        OrderDao orderDao = new OrderDao(db);
        ReportsDao reportsDao = new ReportsDao(db);
        MenuDao menuDao = new MenuDao(db);
        MenuItem item = anyItem(menuDao);
        int variantId = item.variants().get(0).id();
        int menuItemId = item.id();

        long baseOrders = count(db, "SELECT count(*) FROM customer_order");
        System.out.println("  starting from " + baseOrders + " orders");

        // ==================================================== LARGE DATA
        section("§18  Large-data generation (bulk SQL, not the DAO)");

        int target = 50_000;
        long t0 = System.currentTimeMillis();
        db.inTransaction(conn -> {
            bulk(conn, """
                INSERT INTO customer_order
                  (order_number, business_date, order_type, status, staff_id, staff_name,
                   customer_phone, delivery_address, table_number,
                   subtotal, discount_total, tax_total, delivery_fee, total, amount_paid,
                   created_at, completed_at, discount_mode)
                SELECT 'STRESS-' || g,
                       CURRENT_DATE - (g %% 365),
                       (ARRAY['DINE_IN','TAKEAWAY','DELIVERY'])[1 + (g %% 3)],
                       (ARRAY['PENDING','PARTIALLY_PAID','PAYMENT_RECEIVED','CANCELLED'])[1 + (g %% 4)],
                       1, 'Stress',
                       '03001234567',
                       CASE WHEN (g %% 3) = 2 THEN 'stress address line long enough' ELSE NULL END,
                       CASE WHEN (g %% 3) = 0 THEN 'T1' ELSE NULL END,
                       600, 0, 0, 0, 600,
                       CASE (g %% 4) WHEN 0 THEN 0 WHEN 1 THEN 300 WHEN 2 THEN 600 ELSE 0 END,
                       now() - (g %% 365) * interval '1 day',
                       CASE WHEN (g %% 4) = 2 THEN now() ELSE NULL END,
                       'NONE'
                FROM generate_series(1, %d) g
                """.formatted(target));
            bulk(conn, """
                INSERT INTO order_line
                  (order_id, line_no, menu_item_id, variant_id, item_name, unit_price, quantity)
                SELECT co.id, 1, %d, %d, 'Stress Item', 600, 1
                FROM customer_order co WHERE co.order_number LIKE 'STRESS-%%'
                """.formatted(menuItemId, variantId));
            return null;
        });
        long genMs = System.currentTimeMillis() - t0;
        long nowOrders = count(db, "SELECT count(*) FROM customer_order");
        long nowLines = count(db, "SELECT count(*) FROM order_line");
        System.out.printf("    generated %d orders + lines in %d ms -> %d orders, %d lines total%n",
            target, genMs, nowOrders, nowLines);
        db.inTransaction(conn -> { bulk(conn, "ANALYZE customer_order"); bulk(conn, "ANALYZE order_line"); return null; });

        // ==================================================== PERF AT SCALE
        section("§17  Query performance at " + nowOrders + " orders");

        LocalDate today = OrderDao.businessDate(java.time.ZonedDateTime.now());
        LocalDate yearAgo = today.minusDays(365);

        long dash = time(() -> orderDao.loadOrders(new OrderDao.OrderFilter(null, null, today, today)));
        check("PERF-DASH", "Dashboard (today) < 500ms", dash < 500, dash + " ms");

        long sum = time(() -> orderDao.dailySummary(new OrderDao.OrderFilter(null, null, today, today)));
        check("PERF-SUM", "Daily summary < 500ms", sum < 500, sum + " ms");

        long best = time(() -> reportsDao.bestSellers(yearAgo, today, 10));
        check("PERF-BEST", "Best sellers, FULL YEAR < 3000ms", best < 3000, best + " ms");

        long hour = time(() -> reportsDao.salesByHour(yearAgo, today));
        check("PERF-HOUR", "Sales by hour, FULL YEAR < 3000ms", hour < 3000, hour + " ms");

        long staff = time(() -> reportsDao.staffTotals(yearAgo, today));
        check("PERF-STAFF", "Staff totals, FULL YEAR < 3000ms", staff < 3000, staff + " ms");

        long yearDash = time(() -> orderDao.loadOrders(new OrderDao.OrderFilter(null, null, yearAgo, today)));
        System.out.println("    (dashboard over a FULL YEAR: " + yearDash + " ms — "
            + count(db, "SELECT count(*) FROM customer_order WHERE business_date >= CURRENT_DATE-365") + " rows)");

        // ==================================================== ORDER CREATION AT SCALE
        section("§17  Order creation latency at " + nowOrders + " orders");
        long[] times = new long[20];
        for (int i = 0; i < 20; i++) {
            OrderDraft d = draft(OrderType.TAKEAWAY);
            d.addLine(line(item, 2));
            long s = System.nanoTime();
            Order o = orderDao.saveOrder(d, 1, "QA");
            times[i] = (System.nanoTime() - s) / 1_000_000;
            trackId(o.id());
        }
        java.util.Arrays.sort(times);
        System.out.printf("    saveOrder: median %d ms, p95 %d ms, max %d ms%n",
            times[10], times[18], times[19]);
        check("PERF-SAVE", "Order creation median < 250ms at scale", times[10] < 250, times[10] + " ms");

        // ==================================================== FAILURE INJECTION
        section("§14  Failure injection — connection killed mid-transaction");

        long beforeKill = count(db, "SELECT count(*) FROM customer_order");
        String killErr = "";
        boolean crashed = false;
        try {
            db.inTransaction(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("INSERT INTO customer_order (order_number, business_date, order_type, status,"
                        + " staff_id, staff_name, customer_phone, subtotal, total, discount_mode)"
                        + " VALUES ('KILLTEST-1', CURRENT_DATE, 'TAKEAWAY', 'PENDING', 1, 'QA',"
                        + " '03001234567', 100, 100, 'NONE')");
                    // Terminate THIS backend from inside its own transaction.
                    st.execute("SELECT pg_terminate_backend(pg_backend_pid())");
                }
                return null;
            });
        } catch (Exception e) {
            killErr = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\n")[0];
        } catch (Throwable t) {
            crashed = true;
            killErr = "NON-EXCEPTION THROWABLE: " + t;
        }
        System.out.println("    kill result: " + killErr);
        check("FAIL-KILL", "Killed transaction throws a handled exception (no Error/crash)",
            !crashed && !killErr.isEmpty(), killErr);

        long afterKill = count(db, "SELECT count(*) FROM customer_order");
        check("FAIL-ROLLBACK", "No partial row survives the killed transaction",
            afterKill == beforeKill
                && count(db, "SELECT count(*) FROM customer_order WHERE order_number='KILLTEST-1'") == 0,
            "before=" + beforeKill + " after=" + afterKill);

        // ==================================================== RECOVERY
        section("§14  Recovery — can the app keep working after the kill?");
        OrderDraft recov = draft(OrderType.TAKEAWAY);
        recov.addLine(line(item, 1));
        Order recovered = null;
        String recovErr = "";
        try {
            recovered = orderDao.saveOrder(recov, 1, "QA");
            trackId(recovered.id());
        } catch (Exception e) {
            recovErr = e.getMessage();
        }
        check("FAIL-RECOVER", "Db auto-reconnects; next order succeeds",
            recovered != null, recovErr.isEmpty() ? "ok" : recovErr);

        // A second one, to be sure the connection is genuinely healthy again
        OrderDraft recov2 = draft(OrderType.DELIVERY);
        recov2.addLine(line(item, 1));
        Order r2 = orderDao.saveOrder(recov2, 1, "QA");
        trackId(r2.id());
        check("FAIL-RECOVER2", "Connection stable after recovery", r2 != null, "");

        // ==================================================== LEAK / LONG RUN
        section("§33  Repeated cycles — memory & connection stability");
        Runtime rt = Runtime.getRuntime();
        System.gc(); Thread.sleep(300);
        long memBefore = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long connBefore = count(db, "SELECT count(*) FROM pg_stat_activity WHERE datname='rms_test'");

        for (int cycle = 0; cycle < 300; cycle++) {
            OrderDraft d = draft(OrderType.TAKEAWAY);
            d.addLine(line(item, 1));
            Order o = orderDao.saveOrder(d, 1, "QA");
            trackId(o.id());
            orderDao.recordPayment(o.id(), o.totals().total(), 1);
            orderDao.loadOrderForPrint(o.id());
            orderDao.dailySummary(new OrderDao.OrderFilter(null, null, today, today));
        }
        System.gc(); Thread.sleep(300);
        long memAfter = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long connAfter = count(db, "SELECT count(*) FROM pg_stat_activity WHERE datname='rms_test'");
        System.out.printf("    300 full cycles: heap %d -> %d MB, connections %d -> %d%n",
            memBefore, memAfter, connBefore, connAfter);
        check("LEAK-CONN", "No connection leak over 300 cycles", connAfter <= connBefore + 1,
            connBefore + " -> " + connAfter);
        check("LEAK-MEM", "Heap growth < 150MB over 300 cycles", (memAfter - memBefore) < 150,
            (memAfter - memBefore) + " MB");

        // ==================================================== CLEANUP
        section("Cleanup");
        db.inTransaction(conn -> {
            bulk(conn, "DELETE FROM order_line WHERE order_id IN (SELECT id FROM customer_order WHERE order_number LIKE 'STRESS-%')");
            bulk(conn, "DELETE FROM order_status_history WHERE order_id IN (SELECT id FROM customer_order WHERE order_number LIKE 'STRESS-%')");
            bulk(conn, "DELETE FROM customer_order WHERE order_number LIKE 'STRESS-%'");
            return null;
        });
        cleanup(db);
        long finalOrders = count(db, "SELECT count(*) FROM customer_order");
        check("CLEANUP", "Back to the pre-stress row count", finalOrders == baseOrders,
            "base=" + baseOrders + " now=" + finalOrders);

        int violations = integritySweep(db, "after stress suite");
        check("SWEEP", "Zero integrity violations", violations == 0, violations + " violation(s)");
        summary("STRESS SUITE");
        System.exit(QA.failed > 0 ? 1 : 0);
    }

    private static void bulk(Connection c, String sql) throws java.sql.SQLException {
        try (Statement st = c.createStatement()) { st.execute(sql); }
    }

    private interface Work { Object run() throws Exception; }

    private static long time(Work w) throws Exception {
        long s = System.nanoTime();
        w.run();
        return (System.nanoTime() - s) / 1_000_000;
    }
}
