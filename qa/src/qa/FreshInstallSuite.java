package qa;

import rps.db.Db;
import rps.db.MenuDao;
import rps.db.Migrations;
import rps.db.OrderDao;
import rps.db.StaffDao;
import rps.model.Category;
import rps.model.DraftLine;
import rps.model.MenuItem;
import rps.model.MenuItemVariant;
import rps.model.Order;
import rps.model.OrderDraft;
import rps.model.OrderType;
import rps.model.Staff;
import rps.print.ReceiptRenderer;
import rps.util.Money;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * Reproduces what happens on the client's machine the very first time the app runs:
 * an empty database, no schema, no menu, no staff. The other suites all assume an
 * already-migrated database with the real menu in it, so nothing else here covers
 * the bootstrap path — which is the one path every new install is guaranteed to take.
 *
 * <p>Refuses to run against a database that already holds orders, so it can never be
 * pointed at the live till by an accidentally-copied db.properties.
 */
public final class FreshInstallSuite {

    public static void main(String[] args) throws Exception {
        System.out.println("FRESH INSTALL SUITE — first run on an empty database");
        Db db = Db.get();

        QA.section("Guard");
        long existingOrders = countOrders(db);
        if (existingOrders > 0) {
            System.out.println("  ABORT: this database already holds " + existingOrders
                + " order(s), so it is not a fresh install target.");
            System.out.println("  Point db.properties at an empty database and re-run.");
            System.exit(2);
        }
        QA.check("FRESH000", "Target database has no orders", true, "empty");

        QA.section("Schema bootstrap");
        long t0 = System.currentTimeMillis();
        Migrations.applyAll(db);
        QA.check("FRESH001", "Migrations apply to an empty database", true,
            (System.currentTimeMillis() - t0) + " ms");

        t0 = System.currentTimeMillis();
        Migrations.applyAll(db);
        QA.check("FRESH002", "Migrations are idempotent on every later start", true,
            (System.currentTimeMillis() - t0) + " ms");

        QA.section("Seeded login and catalogue");
        StaffDao staffDao = new StaffDao(db);
        Staff admin = staffDao.authenticate(1, "admin123");
        QA.check("FRESH003", "Default manager account can sign in", admin != null,
            admin == null ? "authentication failed" : admin.fullName() + " / " + admin.role());
        QA.check("FRESH004", "Wrong password is rejected", staffDao.authenticate(1, "wrong") == null, "");

        MenuDao menuDao = new MenuDao(db);
        List<Category> categories = menuDao.listCategories(true);
        List<MenuItem> items = menuDao.listAvailableItems();
        QA.check("FRESH005", "Real menu is seeded and orderable", !items.isEmpty(),
            categories.size() + " categories, " + items.size() + " items");

        QA.section("First order end to end");
        MenuItem item = items.stream()
            .filter(m -> !m.variants().isEmpty() && !m.hasOptions())
            .findFirst()
            .orElseGet(() -> items.stream().filter(m -> !m.variants().isEmpty()).findFirst().orElseThrow());
        MenuItemVariant variant = item.variants().get(0);

        OrderDraft draft = new OrderDraft();
        draft.setType(OrderType.TAKEAWAY);
        draft.setCustomerPhone("03001234567");
        draft.addLine(new DraftLine(variant.id(), item.name() + " " + variant.sizeLabel(),
            variant.price(), 2, null, null, null, null));
        draft.setCashTendered(Money.of("10000"));

        Order order = new OrderDao(db).saveOrder(draft, admin.id(), admin.fullName());
        QA.check("FRESH006", "First order saves and settles", order != null,
            order.orderNumber() + "  " + order.totals().total() + "  " + order.status().label());
        QA.check("FRESH007", "Order number is the first of the day",
            order.orderNumber().matches("\\d{8}-001"), order.orderNumber());

        ReceiptRenderer renderer = new ReceiptRenderer(48);
        String customer = renderer.customerReceipt(order);
        String kitchen = renderer.kitchenTicket(order);
        int widest = Math.max(
            customer.lines().mapToInt(String::length).max().orElse(0),
            kitchen.lines().mapToInt(String::length).max().orElse(0));
        QA.check("FRESH008", "Both receipts fit the 48-column roll", widest <= 48, "widest=" + widest);
        QA.check("FRESH009", "Receipt shows the stored total",
            customer.contains(order.totals().total().format()), order.totals().total().format());

        QA.section("Shipped default password must not survive");
        QA.check("FRESH010", "Seeded admin123 is rejected as a real password",
            rps.util.Validators.isUnacceptablePassword("admin123"), "flagged for forced change");
        QA.check("FRESH011", "A strong password is accepted by the policy",
            rps.util.Validators.passwordProblem("Sahowala!Till24") == null, "");
        QA.expectReject("FRESH012", "Changing to a short password is refused",
            () -> staffDao.changePassword(admin.id(), "abc123"));
        QA.expectReject("FRESH013", "Changing to another known default is refused",
            () -> staffDao.changePassword(admin.id(), "password123"));

        staffDao.changePassword(admin.id(), "Sahowala!Till24");
        QA.check("FRESH014", "New password signs in",
            staffDao.authenticate(admin.id(), "Sahowala!Till24") != null, "");
        QA.check("FRESH015", "Old default no longer signs in",
            staffDao.authenticate(admin.id(), "admin123") == null, "");

        QA.summary("FRESH INSTALL SUITE");
        System.exit(QA.failed > 0 ? 1 : 0);
    }

    /** Zero when the table does not exist yet, which is the normal fresh-install case.
     *  The existence check has to be its own statement: Postgres plans every branch of a
     *  CASE, so referencing a missing table inside one still fails at plan time. */
    private static long countOrders(Db db) throws Exception {
        return db.inReadOnly(conn -> {
            try (PreparedStatement exists = conn.prepareStatement(
                    "SELECT to_regclass('public.customer_order') IS NOT NULL");
                 ResultSet rs = exists.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) return 0L;
            }
            try (PreparedStatement count = conn.prepareStatement("SELECT count(*) FROM customer_order");
                 ResultSet rs = count.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }
}
