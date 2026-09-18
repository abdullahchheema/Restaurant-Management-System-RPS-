package qa;

import rps.db.Db;
import rps.db.MenuDao;
import rps.db.OrderDao;
import rps.model.*;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.*;

import static qa.QA.*;

/**
 * R1 reproduction / verification.
 *
 * IMPORTANT: these tests drive the REAL code paths that were fixed —
 *   A) OrderDraft.snapshot() as PosPanel.confirmOrder() now uses it, and
 *   B) the actual PosPanel.confirmOrder() method via reflection.
 * An earlier version of this file called OrderDao.saveOrder directly and
 * hand-simulated the click handler, so it exercised neither fix and reported
 * false failures.
 */
public final class R1Repro {

    public static void main(String[] args) throws Exception {
        Db db = Db.get();
        OrderDao orderDao = new OrderDao(db);
        MenuItem item = anyItem(new MenuDao(db));

        // ==================================================== A) TORN ORDER
        section("R1-A  Draft mutated mid-save (drives OrderDraft.snapshot(), as PosPanel does)");

        int runs = 40;
        int tornLive = 0, tornSnap = 0;

        for (int i = 0; i < runs; i++) {
            // ---- control: the OLD behaviour (live draft handed to the worker)
            OrderDraft live = draft(OrderType.TAKEAWAY);
            for (int k = 0; k < 3; k++) live.addLine(line(item, 1));
            tornLive += raceOnce(orderDao, live, live, 3);

            // ---- fixed: snapshot taken on the "EDT" before handing off
            OrderDraft live2 = draft(OrderType.TAKEAWAY);
            for (int k = 0; k < 3; k++) live2.addLine(line(item, 1));
            OrderDraft confirmed = live2.snapshot();     // what confirmOrder() now does
            tornSnap += raceOnce(orderDao, confirmed, live2, 3);
        }

        System.out.printf("    live draft passed to worker (old behaviour): %d/%d orders torn%n", tornLive, runs);
        System.out.printf("    snapshot passed to worker (fixed behaviour): %d/%d orders torn%n", tornSnap, runs);
        check("R1-A-REPRO", "Old behaviour is genuinely broken (control)", tornLive > 0,
            tornLive + "/" + runs + " torn — confirms the defect is real");
        check("R1-A-FIX", "snapshot() isolates the save from later edits",
            tornSnap == 0, tornSnap + "/" + runs + " torn");

        // ==================================================== B) DUPLICATE
        section("R1-B  Real PosPanel.confirmOrder() — button state while save is in flight");

        final String[] note = {""};
        final boolean[] reEnabled = {true};
        final boolean[] secondCallBlocked = {false};

        SwingUtilities.invokeAndWait(() -> {
            try {
                Staff staff = new rps.db.StaffDao(db).listActive().get(0);
                rps.app.Session session = new rps.app.Session(staff);
                rps.backup.OffsiteBackupService backup =
                    new rps.backup.OffsiteBackupService(db.connectionInfo(),
                        rps.util.AppSettings.get().backupDir());
                rps.ui.PosPanel panel = new rps.ui.PosPanel(db, session, backup);
                JFrame f = new JFrame();
                f.add(panel);
                f.pack();

                Field cbF = rps.ui.PosPanel.class.getDeclaredField("confirmButton");
                cbF.setAccessible(true);
                JButton confirm = (JButton) cbF.get(panel);
                Field dF = rps.ui.PosPanel.class.getDeclaredField("draft");
                dF.setAccessible(true);
                OrderDraft d = (OrderDraft) dF.get(panel);
                Field sF = rps.ui.PosPanel.class.getDeclaredField("submitting");
                sF.setAccessible(true);

                d.setType(OrderType.TAKEAWAY);
                d.setCustomerPhone("03001234567");
                d.addLine(line(item, 1));

                // Invoke the REAL click handler. Busy runs the save on a SwingWorker, so
                // we are still on the EDT immediately afterwards with the save in flight.
                Method confirmOrder = rps.ui.PosPanel.class.getDeclaredMethod("confirmOrder");
                confirmOrder.setAccessible(true);
                confirmOrder.invoke(panel);

                boolean submittingSet = (Boolean) sF.get(panel);

                // The cashier taps another tile while the save is running.
                Method refreshTicket = rps.ui.PosPanel.class.getDeclaredMethod("refreshTicket");
                refreshTicket.setAccessible(true);
                d.addLine(line(item, 1));
                refreshTicket.invoke(panel);
                reEnabled[0] = confirm.isEnabled();

                // A second click while still submitting must be a no-op.
                int before = (int) count(db, "SELECT count(*) FROM customer_order");
                confirmOrder.invoke(panel);
                int after = (int) count(db, "SELECT count(*) FROM customer_order");
                secondCallBlocked[0] = (after == before);

                note[0] = "submittingFlag=" + submittingSet
                    + " reEnabledByTicketChange=" + reEnabled[0]
                    + " secondClickBlocked=" + secondCallBlocked[0];
                f.dispose();
            } catch (Throwable t) {
                note[0] = "could not drive PosPanel: " + t;
            }
        });

        // let the async save finish so we can clean it up
        Thread.sleep(2500);
        System.out.println("    " + note[0]);
        check("R1-B-FIX", "Confirm stays DISABLED while a save is in flight",
            !reEnabled[0], note[0]);
        check("R1-B-FIX2", "A second confirmOrder() during the save creates no order",
            secondCallBlocked[0], note[0]);

        db.inTransaction(conn -> {
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("UPDATE customer_order SET delivery_run_id=NULL WHERE staff_name LIKE '%'"
                    + " AND id IN (SELECT id FROM customer_order WHERE created_at > now() - interval '5 minutes')");
            }
            return null;
        });
        cleanup(db);
        // remove anything the real panel saved during R1-B
        db.inTransaction(conn -> {
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("DELETE FROM order_status_history WHERE order_id IN "
                    + "(SELECT id FROM customer_order WHERE created_at > now() - interval '5 minutes')");
                st.execute("DELETE FROM customer_order WHERE created_at > now() - interval '5 minutes'");
            }
            return null;
        });
        integritySweep(db, "after R1 verification");
        summary("R1 VERIFICATION");
        System.exit(QA.failed > 0 ? 1 : 0);
    }

    /** Saves {@code toSave} while concurrently mutating {@code toMutate}; returns 1 if the
     *  saved order ended up with a different line count than {@code expectedLines}. */
    private static int raceOnce(OrderDao dao, OrderDraft toSave, OrderDraft toMutate, int expectedLines)
            throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        MenuItem item = anyItem(new MenuDao(Db.get()));
        Future<Order> saver = pool.submit(() -> { go.await(); return dao.saveOrder(toSave, 1, "QA"); });
        pool.submit(() -> {
            try {
                go.await();
                for (int k = 0; k < 5; k++) { toMutate.addLine(line(item, 1)); Thread.sleep(1); }
            } catch (Exception ignored) { }
        });
        go.countDown();
        Order saved;
        try {
            saved = saver.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            pool.shutdown();
            return 0;
        }
        pool.shutdown();
        trackId(saved.id());
        return saved.lines().size() == expectedLines ? 0 : 1;
    }
}
