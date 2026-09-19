package rps.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Ordered schema migrations. applyAll() runs every pending migration inside ONE
 * transaction (not one transaction per migration — Postgres DDL is transactional, and
 * running the whole pending batch as a unit means a failure partway through leaves the
 * database exactly as it was, with schema_version reflecting nothing half-applied).
 *
 * Ordering matters and is not arbitrary — see plan Phase 1:
 *   1. Rewrite legacy delete-all-then-reinsert staff/menu saves away (done in code, not here;
 *      StaffDao/legacy MenuDao no longer exist — this is a fresh schema, not a migration of
 *      the old one, since the old app only ever had 1 staff row and 1 test item).
 *   2. Create new tables -> indexes -> triggers -> seed.
 *   3. Drop the old orders/order_details tables (proven empty: never written by any code path).
 */
public final class Migrations {

    private Migrations() {}

    public static void applyAll(Db db) throws DatabaseException {
        db.inTransaction(conn -> {
            ensureVersionTable(conn);
            for (Migration m : ALL) {
                if (!isApplied(conn, m.version)) {
                    runMigration(conn, m);
                }
            }
            applyPasswordHashing(conn);
            return null;
        });
    }

    private static void ensureVersionTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INT PRIMARY KEY,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    description TEXT
                )
                """);
        }
    }

    private static boolean isApplied(Connection conn, int version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM schema_version WHERE version = ?")) {
            ps.setInt(1, version);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void runMigration(Connection conn, Migration m) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String sql : m.statements) {
                st.execute(sql);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO schema_version (version, description) VALUES (?, ?)")) {
            ps.setInt(1, m.version);
            ps.setString(2, m.description);
            ps.executeUpdate();
        }
    }

    private record Migration(int version, String description, List<String> statements) {}

    private static final List<Migration> ALL = buildMigrations();

    private static List<Migration> buildMigrations() {
        List<Migration> list = new ArrayList<>();

        list.add(new Migration(1, "drop legacy unused order tables + old menu/staff tables", List.of(
            "DROP TABLE IF EXISTS order_details",
            "DROP TABLE IF EXISTS orders",
            "DROP TABLE IF EXISTS menu_items",
            // old staff table is superseded by the new one below; only ever held 1 row (id=1, admin)
            "DROP TABLE IF EXISTS staff"
        )));

        list.add(new Migration(2, "staff + role", List.of(
            """
            CREATE TABLE staff (
                id SERIAL PRIMARY KEY,
                password VARCHAR(255) NOT NULL,
                first_name VARCHAR(120) NOT NULL,
                last_name VARCHAR(120) NOT NULL,
                role VARCHAR(10) NOT NULL CHECK (role IN ('MANAGER','EMPLOYEE')),
                active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """,
            // Re-seed the admin manager account (matches the login already in use).
            """
            INSERT INTO staff (id, password, first_name, last_name, role)
            VALUES (1, 'admin123', 'Admin', 'User', 'MANAGER')
            """,
            "SELECT setval(pg_get_serial_sequence('staff','id'), (SELECT MAX(id) FROM staff))"
        )));

        list.add(new Migration(3, "category / menu_item / menu_item_variant", List.of(
            """
            CREATE TABLE category (
                id SERIAL PRIMARY KEY,
                name VARCHAR(80) NOT NULL UNIQUE,
                display_order INT NOT NULL DEFAULT 0,
                active BOOLEAN NOT NULL DEFAULT TRUE
            )
            """,
            """
            CREATE TABLE menu_item (
                id SERIAL PRIMARY KEY,
                category_id INT NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
                name VARCHAR(120) NOT NULL,
                description TEXT,
                sized BOOLEAN NOT NULL DEFAULT FALSE,
                available BOOLEAN NOT NULL DEFAULT TRUE,
                deleted_at TIMESTAMPTZ,
                display_order INT NOT NULL DEFAULT 0,
                CHECK (length(btrim(name)) > 0)
            )
            """,
            """
            CREATE UNIQUE INDEX ux_menu_item_live_name
                ON menu_item (category_id, lower(btrim(name)))
                WHERE deleted_at IS NULL
            """,
            "CREATE INDEX ix_menu_item_category ON menu_item (category_id)",
            """
            CREATE TABLE menu_item_variant (
                id SERIAL PRIMARY KEY,
                menu_item_id INT NOT NULL REFERENCES menu_item(id) ON DELETE CASCADE,
                size_label VARCHAR(30),
                price NUMERIC(12,2) NOT NULL CHECK (price >= 0),
                available BOOLEAN NOT NULL DEFAULT TRUE,
                deleted_at TIMESTAMPTZ,
                display_order INT NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE UNIQUE INDEX ux_variant_live_size
                ON menu_item_variant (menu_item_id, size_label) NULLS NOT DISTINCT
                WHERE deleted_at IS NULL
            """
        )));

        list.add(new Migration(4, "customer_order / order_line / daily_counter / status_history", List.of(
            """
            CREATE TABLE daily_counter (
                business_date DATE PRIMARY KEY,
                last_seq INT NOT NULL CHECK (last_seq > 0)
            )
            """,
            """
            CREATE TABLE customer_order (
                id BIGSERIAL PRIMARY KEY,
                order_number VARCHAR(20) NOT NULL UNIQUE,
                business_date DATE NOT NULL,
                order_type VARCHAR(10) NOT NULL CHECK (order_type IN ('DINE_IN','TAKEAWAY','DELIVERY')),
                status VARCHAR(12) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','PREPARING','READY','COMPLETED','CANCELLED')),
                staff_id INT REFERENCES staff(id) ON DELETE SET NULL,
                staff_name VARCHAR(120) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                completed_at TIMESTAMPTZ,
                subtotal NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
                discount_total NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
                tax_total NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (tax_total >= 0),
                total NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
                table_number VARCHAR(10),
                customer_name VARCHAR(120),
                customer_phone VARCHAR(20),
                delivery_address TEXT,
                notes TEXT,
                CHECK (order_type <> 'DELIVERY' OR (
                    customer_phone IS NOT NULL AND length(btrim(customer_phone)) > 0
                    AND delivery_address IS NOT NULL AND length(btrim(delivery_address)) >= 10
                )),
                CHECK (total = subtotal - discount_total + tax_total)
            )
            """,
            "CREATE INDEX ix_order_bizdate ON customer_order (business_date)",
            "CREATE INDEX ix_order_created ON customer_order (created_at DESC)",
            "CREATE INDEX ix_order_type_bizdate ON customer_order (order_type, business_date)",
            """
            CREATE INDEX ix_order_active ON customer_order (status)
                WHERE status IN ('PENDING','PREPARING','READY')
            """,
            """
            CREATE TABLE order_line (
                id BIGSERIAL PRIMARY KEY,
                order_id BIGINT NOT NULL REFERENCES customer_order(id) ON DELETE CASCADE,
                line_no INT NOT NULL,
                menu_item_id INT REFERENCES menu_item(id) ON DELETE RESTRICT,
                variant_id INT REFERENCES menu_item_variant(id) ON DELETE RESTRICT,
                item_name VARCHAR(120) NOT NULL,
                size_label VARCHAR(30),
                unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
                quantity INT NOT NULL CHECK (quantity > 0 AND quantity <= 999),
                line_total NUMERIC(14,2) GENERATED ALWAYS AS (unit_price * quantity) STORED,
                notes TEXT,
                UNIQUE (order_id, line_no)
            )
            """,
            "CREATE INDEX ix_line_order ON order_line (order_id)",
            "CREATE INDEX ix_line_menu_item ON order_line (menu_item_id)",
            "CREATE INDEX ix_line_variant ON order_line (variant_id)",
            """
            CREATE TABLE order_status_history (
                id BIGSERIAL PRIMARY KEY,
                order_id BIGINT NOT NULL REFERENCES customer_order(id) ON DELETE CASCADE,
                from_status VARCHAR(12),
                to_status VARCHAR(12) NOT NULL,
                changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                staff_id INT REFERENCES staff(id) ON DELETE SET NULL
            )
            """,
            "CREATE INDEX ix_status_history_order ON order_status_history (order_id)"
        )));

        list.add(new Migration(5, "updated_at trigger on customer_order", List.of(
            """
            CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS trigger AS $$
            BEGIN
                NEW.updated_at := now();
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """,
            """
            CREATE TRIGGER trg_order_touch
                BEFORE UPDATE ON customer_order
                FOR EACH ROW EXECUTE FUNCTION touch_updated_at()
            """
        )));

        list.add(new Migration(6, "seed categories and menu", Seed.statements()));

        list.add(new Migration(7, "simplify order status to COMPLETED/CANCELLED, default COMPLETED", List.of(
            // Existing orders in a since-removed in-progress state become COMPLETED —
            // there is no "in progress" concept left to preserve them as.
            "UPDATE customer_order SET status = 'COMPLETED' WHERE status IN ('PENDING','PREPARING','READY')",
            "UPDATE customer_order SET completed_at = created_at WHERE status = 'COMPLETED' AND completed_at IS NULL",
            "ALTER TABLE customer_order DROP CONSTRAINT customer_order_status_check",
            "ALTER TABLE customer_order ALTER COLUMN status SET DEFAULT 'COMPLETED'",
            "ALTER TABLE customer_order ADD CONSTRAINT customer_order_status_check CHECK (status IN ('COMPLETED','CANCELLED'))",
            // The old partial index only covered in-progress statuses that no longer exist.
            "DROP INDEX IF EXISTS ix_order_active"
        )));

        list.add(new Migration(8, "discount + cash tendered, phone mandatory for every order type", List.of(
            "ALTER TABLE customer_order ADD COLUMN discount_mode VARCHAR(8) NOT NULL DEFAULT 'NONE'",
            "ALTER TABLE customer_order ADD COLUMN discount_rate NUMERIC(5,2)",
            "ALTER TABLE customer_order ADD COLUMN cash_tendered NUMERIC(12,2)",

            "ALTER TABLE customer_order ADD CONSTRAINT ck_discount_mode "
                + "CHECK (discount_mode IN ('NONE','PERCENT','AMOUNT'))",
            "ALTER TABLE customer_order ADD CONSTRAINT ck_discount_rate_range "
                + "CHECK (discount_rate IS NULL OR (discount_rate >= 0 AND discount_rate <= 100))",
            // rate is present exactly when mode is PERCENT
            "ALTER TABLE customer_order ADD CONSTRAINT ck_discount_rate_presence "
                + "CHECK ((discount_mode = 'PERCENT') = (discount_rate IS NOT NULL))",
            // structural backstop alongside the LEAST() clamp in OrderDao.rollupTotals
            "ALTER TABLE customer_order ADD CONSTRAINT ck_discount_le_subtotal "
                + "CHECK (discount_total <= subtotal)",
            "ALTER TABLE customer_order ADD CONSTRAINT ck_cash_tendered_nonneg "
                + "CHECK (cash_tendered IS NULL OR cash_tendered >= 0)",

            // Phone becomes mandatory for every order type (was delivery-only). The
            // existing sample orders are demo data seeded before phone was required and
            // carry no honest value to backfill, so they are removed rather than
            // grandfathered — grandfathering by id would need a second, permanent
            // exception clause in the CHECK, and a NOT VALID constraint would still be
            // enforced on every future UPDATE of those legacy rows (e.g. cancelling
            // one), which is worse than deleting five rows of seed data.
            "DELETE FROM customer_order WHERE customer_phone IS NULL OR btrim(customer_phone) = ''",
            "ALTER TABLE customer_order ADD CONSTRAINT ck_phone_required "
                + "CHECK (customer_phone IS NOT NULL AND length(btrim(customer_phone)) > 0)"
        )));

        // Replaces migration 6's placeholder demo catalog with the shop's real printed
        // menu. Old rows are soft-deleted/deactivated, never hard-deleted: order_line.
        // menu_item_id is ON DELETE RESTRICT, so any historical order referencing the old
        // catalog would abort this migration outright, and deleting menu_item_variant
        // rows out from under it would do the same via variant_id. category.name is also
        // UNIQUE, so the old categories are renamed off (not just deactivated) before the
        // real ones are inserted under their real names — otherwise "Pizzas" would collide
        // with itself.
        List<String> retireOldMenu = new ArrayList<>(List.of(
            "UPDATE menu_item SET deleted_at = now(), available = FALSE WHERE deleted_at IS NULL",
            "UPDATE menu_item_variant SET deleted_at = now(), available = FALSE WHERE deleted_at IS NULL",
            "UPDATE category SET active = FALSE, name = 'Legacy: ' || name WHERE active = TRUE"
        ));
        retireOldMenu.addAll(RealMenuSeed.statements());
        list.add(new Migration(9, "replace demo catalog with the real Royal Pizza Sahowala menu", retireOldMenu));

        list.add(new Migration(10, "delivery fee for under-threshold delivery orders", List.of(
            "ALTER TABLE customer_order ADD COLUMN delivery_fee NUMERIC(12,2) NOT NULL DEFAULT 0",
            "ALTER TABLE customer_order ADD CONSTRAINT ck_delivery_fee_nonneg CHECK (delivery_fee >= 0)",

            // The original total-equation CHECK (migration 4) was inline in CREATE TABLE
            // and never named, so Postgres auto-named it — it has to be found and dropped
            // by its definition before delivery_fee can be added to the formula, or every
            // future order with a nonzero delivery_fee would violate it immediately.
            """
            DO $$
            DECLARE con_name text;
            BEGIN
                SELECT conname INTO con_name
                FROM pg_constraint
                WHERE conrelid = 'customer_order'::regclass
                  AND contype = 'c'
                  AND pg_get_constraintdef(oid) ILIKE '%total = %subtotal%discount_total%tax_total%'
                  AND pg_get_constraintdef(oid) NOT ILIKE '%delivery_fee%';
                IF con_name IS NOT NULL THEN
                    EXECUTE 'ALTER TABLE customer_order DROP CONSTRAINT ' || quote_ident(con_name);
                END IF;
            END $$
            """,
            "ALTER TABLE customer_order ADD CONSTRAINT ck_total_equation "
                + "CHECK (total = subtotal - discount_total + tax_total + delivery_fee)"
        )));

        list.add(new Migration(11, "phone optional for dine-in", List.of(
            // Migration 8's ck_phone_required made phone mandatory for every order type.
            // Dine-in doesn't need it — there's no one to call and no address to confirm
            // it against, unlike Takeaway (pickup contact) and Delivery (already has its
            // own stricter phone+address check from migration 4). Named constraint, so
            // no lookup-by-definition trickery needed to drop it, unlike ck_total_equation.
            "ALTER TABLE customer_order DROP CONSTRAINT ck_phone_required",
            "ALTER TABLE customer_order ADD CONSTRAINT ck_phone_required "
                + "CHECK (order_type = 'DINE_IN' "
                + "OR (customer_phone IS NOT NULL AND length(btrim(customer_phone)) > 0))"
        )));

        // Migration 12 is applied in Java, not SQL: PBKDF2 hashing can't be expressed in
        // plain SQL without pgcrypto, and requiring a server extension on the client's
        // machine would be a new install-time dependency. See applyPasswordHashing().

        list.add(new Migration(13, "option groups, table-number/status rework, cumulative payments", List.of(
            """
            CREATE TABLE option_group (
                id SERIAL PRIMARY KEY,
                name VARCHAR(60) NOT NULL UNIQUE,
                display_order INT NOT NULL DEFAULT 0,
                active BOOLEAN NOT NULL DEFAULT TRUE
            )
            """,
            """
            CREATE TABLE option_value (
                id SERIAL PRIMARY KEY,
                option_group_id INT NOT NULL REFERENCES option_group(id) ON DELETE CASCADE,
                name VARCHAR(60) NOT NULL,
                display_order INT NOT NULL DEFAULT 0,
                available BOOLEAN NOT NULL DEFAULT TRUE,
                deleted_at TIMESTAMPTZ
            )
            """,
            """
            CREATE UNIQUE INDEX ux_option_value_live_name
                ON option_value (option_group_id, lower(btrim(name))) WHERE deleted_at IS NULL
            """,
            "ALTER TABLE menu_item ADD COLUMN option_group_id INT REFERENCES option_group(id) ON DELETE SET NULL",

            "ALTER TABLE order_line ADD COLUMN option_value_id INT REFERENCES option_value(id) ON DELETE SET NULL",
            "ALTER TABLE order_line ADD COLUMN option_group_name VARCHAR(60)",
            "ALTER TABLE order_line ADD COLUMN option_value_name VARCHAR(60)",

            // 'PAYMENT_RECEIVED' is 16 characters; both status columns were VARCHAR(12).
            "ALTER TABLE customer_order ALTER COLUMN status TYPE VARCHAR(20)",
            "ALTER TABLE order_status_history ALTER COLUMN from_status TYPE VARCHAR(20)",
            "ALTER TABLE order_status_history ALTER COLUMN to_status TYPE VARCHAR(20)",

            "ALTER TABLE customer_order ADD COLUMN amount_paid NUMERIC(12,2) NOT NULL DEFAULT 0",
            "ALTER TABLE customer_order ADD CONSTRAINT ck_amount_paid_nonneg CHECK (amount_paid >= 0)",

            // The old CHECK only allows ('COMPLETED','CANCELLED') — it must be dropped
            // BEFORE the status remap below, or writing 'PAYMENT_RECEIVED' into any row
            // violates it. The new CHECK is added back only after every row already
            // carries a value it accepts.
            "ALTER TABLE customer_order DROP CONSTRAINT customer_order_status_check",

            // Every historical order was, by definition, fully paid under the old
            // COMPLETED-on-confirm model, so backfilling amount_paid = total and mapping
            // COMPLETED -> PAYMENT_RECEIVED keeps every existing revenue figure identical.
            "UPDATE customer_order SET amount_paid = total WHERE status = 'COMPLETED'",
            "UPDATE customer_order SET status = 'PAYMENT_RECEIVED' WHERE status = 'COMPLETED'",
            "UPDATE order_status_history SET to_status = 'PAYMENT_RECEIVED' WHERE to_status = 'COMPLETED'",
            "UPDATE order_status_history SET from_status = 'PAYMENT_RECEIVED' WHERE from_status = 'COMPLETED'",

            "ALTER TABLE customer_order ALTER COLUMN status SET DEFAULT 'PENDING'",
            "ALTER TABLE customer_order ADD CONSTRAINT customer_order_status_check "
                + "CHECK (status IN ('PENDING','PAYMENT_RECEIVED','CANCELLED'))"
        )));

        list.add(new Migration(14, "riders and delivery runs", List.of(
            """
            CREATE TABLE rider (
                id SERIAL PRIMARY KEY,
                name VARCHAR(80) NOT NULL,
                phone VARCHAR(20),
                active BOOLEAN NOT NULL DEFAULT TRUE
            )
            """,
            """
            CREATE TABLE delivery_run (
                id BIGSERIAL PRIMARY KEY,
                rider_id INT NOT NULL REFERENCES rider(id) ON DELETE RESTRICT,
                status VARCHAR(12) NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN','COMPLETED','CANCELLED')),
                collected_amount NUMERIC(12,2) CHECK (collected_amount IS NULL OR collected_amount >= 0),
                staff_id INT REFERENCES staff(id) ON DELETE SET NULL,
                staff_name VARCHAR(120) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                closed_at TIMESTAMPTZ
            )
            """,
            "CREATE INDEX ix_delivery_run_status ON delivery_run (status)",

            // Nullable FK directly on the order, not a join table — an order is out
            // with at most one rider at a time, never many-to-many, so this is the
            // direct expression of that relationship (same reasoning as menu_item.
            // option_group_id in migration 13).
            "ALTER TABLE customer_order ADD COLUMN delivery_run_id BIGINT REFERENCES delivery_run(id) ON DELETE SET NULL",
            "CREATE INDEX ix_order_delivery_run ON customer_order (delivery_run_id) WHERE delivery_run_id IS NOT NULL"
        )));

        list.add(new Migration(15, "PARTIALLY_PAID order status", List.of(
            // Widen the CHECK before writing any row that needs the new value —
            // the reverse order fails on the first UPDATE (see migration 13's ordering).
            "ALTER TABLE customer_order DROP CONSTRAINT customer_order_status_check",
            "ALTER TABLE customer_order ADD CONSTRAINT customer_order_status_check "
                + "CHECK (status IN ('PENDING','PARTIALLY_PAID','PAYMENT_RECEIVED','CANCELLED'))",

            // Existing rows carrying a part-payment were indistinguishable from
            // never-paid ones under the old two-state model; reclassify them from the
            // amounts already stored. PAYMENT_RECEIVED and CANCELLED rows are left
            // alone, so no revenue figure moves.
            "UPDATE customer_order SET status = 'PARTIALLY_PAID' "
                + "WHERE status = 'PENDING' AND amount_paid > 0 AND amount_paid < total"

            // status stays VARCHAR(20) (migration 13) — 'PARTIALLY_PAID' is 14 chars —
            // and order_status_history's columns were widened to 20 there too, so
            // neither needs altering here.
        )));

        // Until now one column answered two unrelated questions: "has the customer paid?"
        // and "is the food ready / has it gone out?". They genuinely are independent — a
        // delivery order is routinely cooked and handed over before any cash arrives, and
        // a dine-in customer can pay up front and still be waiting. Conflating them meant
        // the counter had no way to say "this one is still being prepared" about an order
        // that happened to be paid, and cancelling was tangled up with payment state.
        list.add(new Migration(16, "split order status into payment_status + fulfilment_status", List.of(
            // ---------------------------------------------------- fulfilment (new track)
            "ALTER TABLE customer_order ADD COLUMN fulfilment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'",

            // Backfilled before the CHECK is added. Under the old model a settled order was
            // one that had been handed over and closed out, so it maps to COMPLETED; a
            // cancelled one stays cancelled; anything still owing money is treated as
            // still in the kitchen, which is the safe direction to be wrong in.
            """
            UPDATE customer_order SET fulfilment_status =
                CASE status
                    WHEN 'CANCELLED'        THEN 'CANCELLED'
                    WHEN 'PAYMENT_RECEIVED' THEN 'COMPLETED'
                    ELSE 'PENDING'
                END
            """,
            "ALTER TABLE customer_order ADD CONSTRAINT ck_fulfilment_status "
                + "CHECK (fulfilment_status IN ('PENDING','COMPLETED','CANCELLED'))",

            // ---------------------------------------------------- status becomes money-only
            "ALTER TABLE customer_order DROP CONSTRAINT customer_order_status_check",

            // A CANCELLED row carried no payment state of its own, so it is reconstructed
            // from the amounts actually stored — otherwise a cancelled order that the
            // customer had already paid would come out looking unpaid.
            """
            UPDATE customer_order SET status =
                CASE
                    WHEN status = 'PAYMENT_RECEIVED'        THEN 'PAID'
                    WHEN status = 'PARTIALLY_PAID'          THEN 'PARTIALLY_PAID'
                    WHEN status = 'PENDING'                 THEN 'UNPAID'
                    WHEN amount_paid >= total AND total > 0 THEN 'PAID'
                    WHEN amount_paid > 0                    THEN 'PARTIALLY_PAID'
                    ELSE 'UNPAID'
                END
            """,
            "ALTER TABLE customer_order RENAME COLUMN status TO payment_status",
            "ALTER TABLE customer_order ALTER COLUMN payment_status SET DEFAULT 'UNPAID'",
            "ALTER TABLE customer_order ADD CONSTRAINT ck_payment_status "
                + "CHECK (payment_status IN ('UNPAID','PARTIALLY_PAID','PAID'))",
            // Both dashboard panes and every reporting query now filter on one or both of
            // these, and there is no surviving index on the old status column — migration 7
            // dropped ix_order_active and nothing replaced it.
            "CREATE INDEX ix_order_payment_status ON customer_order (business_date, payment_status)",
            "CREATE INDEX ix_order_fulfilment ON customer_order (business_date, fulfilment_status)",

            // ---------------------------------------------------- history says which track moved
            "ALTER TABLE order_status_history ADD COLUMN status_kind VARCHAR(12) NOT NULL DEFAULT 'PAYMENT'",
            "UPDATE order_status_history SET status_kind = 'FULFILMENT' "
                + "WHERE to_status = 'CANCELLED' OR from_status = 'CANCELLED'",
            "UPDATE order_status_history SET to_status = 'PAID' WHERE to_status = 'PAYMENT_RECEIVED'",
            "UPDATE order_status_history SET from_status = 'PAID' WHERE from_status = 'PAYMENT_RECEIVED'",
            "UPDATE order_status_history SET to_status = 'UNPAID' "
                + "WHERE to_status = 'PENDING' AND status_kind = 'PAYMENT'",
            "UPDATE order_status_history SET from_status = 'UNPAID' "
                + "WHERE from_status = 'PENDING' AND status_kind = 'PAYMENT'",
            "ALTER TABLE order_status_history ADD CONSTRAINT ck_status_kind "
                + "CHECK (status_kind IN ('PAYMENT','FULFILMENT'))"
        )));

        // A photo per menu item, uploaded from the manager's own device (see
        // MenuItemEditorDialog / rps.util.ImageUtil). Stored as bytes IN the database
        // rather than as a file path on disk: this app already treats the database as the
        // one thing that gets backed up (OffsiteBackupService dumps it, nothing else), and
        // a filesystem path would mean either the image silently isn't backed up, or a
        // second backup mechanism to keep in sync with the first. Every upload is resized
        // and re-encoded to a small JPEG before it ever reaches this column, so a photo
        // straight off a phone does not bloat every row or the POS's polling payload.
        list.add(new Migration(17, "menu item photo", List.of(
            "ALTER TABLE menu_item ADD COLUMN image BYTEA"
        )));

        // Lets a cashier waive the phone requirement for a Takeaway/Delivery customer
        // who won't give one (OrderDraft.phoneNotRequired / the "No phone number" toggle
        // on PosPanel and OrderEditDialog). Migration 11's ck_phone_required enforced
        // "phone required unless Dine-in" as a database invariant, which is exactly what
        // this feature needs to NOT be true any more — it is now a per-order business
        // decision the cashier makes, not something every row must satisfy regardless.
        // Reproduced live: without this, saveOrder() passed its own Java-side check
        // (OrderDraft.isCustomerInfoValid already respects the toggle) and then failed
        // at the INSERT with a raw constraint-violation error instead of saving. No
        // replacement constraint is added — Validators.isValidPakistaniPhone still runs
        // in Java on any phone that IS entered, toggle or not, so a well-formed-or-absent
        // phone is still all that is ever accepted; this migration only removes the part
        // that forced one to be present.
        list.add(new Migration(18, "phone requirement is a business choice, not a DB constraint", List.of(
            "ALTER TABLE customer_order DROP CONSTRAINT ck_phone_required"
        )));

        // Migration 4's original, inline (never named) CHECK on customer_order was its own
        // separate rule for Delivery orders specifically: phone non-blank AND address at
        // least 10 characters. Migration 18 above only dropped the LATER, general-purpose
        // ck_phone_required (added in migration 8) — it never touched this older one, so
        // a Delivery order saved with the phone-not-required toggle ticked and a blank
        // phone would still have failed at the INSERT with a raw constraint violation,
        // exactly the migration-18 bug but for Delivery instead of Takeaway. Dropping it
        // by definition, not by name, because it was never named — same technique as
        // ck_total_equation in migration 10.
        list.add(new Migration(19, "delivery address no longer has a 10-character floor; phone toggle applies to delivery too", List.of(
            """
            DO $$
            DECLARE con_name text;
            BEGIN
                SELECT conname INTO con_name
                FROM pg_constraint
                WHERE conrelid = 'customer_order'::regclass
                  AND contype = 'c'
                  AND pg_get_constraintdef(oid) ILIKE '%delivery_address%'
                  AND pg_get_constraintdef(oid) ILIKE '%10%';
                IF con_name IS NOT NULL THEN
                    EXECUTE 'ALTER TABLE customer_order DROP CONSTRAINT ' || quote_ident(con_name);
                END IF;
            END $$
            """,
            // Replacement only requires a non-blank address for Delivery — no minimum
            // length, and no phone requirement at all: whether a phone is mandatory is
            // now entirely OrderDraft.phoneNotRequired's call, enforced in Java exactly
            // the same way for every order type, not duplicated here.
            "ALTER TABLE customer_order ADD CONSTRAINT ck_delivery_address_required "
                + "CHECK (order_type <> 'DELIVERY' OR "
                + "(delivery_address IS NOT NULL AND length(btrim(delivery_address)) > 0))"
        )));

        // "Pay later" / loan: a customer walks out owing money, by agreement. This is a
        // THIRD track, deliberately not a payment_status value — payment_status is derived
        // from amount_paid on every applyPayment call, so anything stored there would be
        // overwritten the moment a customer pays part of what they owe, which is exactly
        // when a loan most needs to stay a loan. loan_at IS NULL / NOT NULL is the flag;
        // storing the timestamp rather than a boolean means "when was credit given" comes
        // free, and loan_staff_id records who agreed to it.
        //
        // The accounting consequence (a deliberate business decision, not an accident):
        // a loan order counts as revenue immediately even though the cash has not arrived,
        // and stops counting as outstanding/unpaid — the money owed is tracked as a
        // receivable in the Pay Later screen instead. See OrderDao#dailySummary.
        list.add(new Migration(20, "pay-later (loan) orders", List.of(
            "ALTER TABLE customer_order ADD COLUMN loan_at TIMESTAMPTZ",
            "ALTER TABLE customer_order ADD COLUMN loan_staff_id INT REFERENCES staff(id)",
            // Partial: only loan rows are ever looked up by this, and they are a small
            // minority of the table — same shape as ix_order_delivery_run above.
            "CREATE INDEX ix_order_loan ON customer_order (loan_at) WHERE loan_at IS NOT NULL",
            // A cancelled order is void: there is no debt to collect, so it can never also
            // be an outstanding loan. Enforced here rather than only in Java because this
            // one IS expressible as a row invariant (unlike the time-window rules).
            "ALTER TABLE customer_order ADD CONSTRAINT ck_loan_not_cancelled "
                + "CHECK (loan_at IS NULL OR fulfilment_status <> 'CANCELLED')"
        )));

        // A debt that has been collected in full is no longer a debt: it leaves the Pay
        // Later ledger and goes back to the Dashboard as an ordinary settled order, which
        // is what this column records. The pair (loan_at, loan_settled_at) is what decides
        // where an order is shown — "on the ledger" is loan_at IS NOT NULL AND
        // loan_settled_at IS NULL, and the Dashboard shows exactly the complement.
        //
        // Kept as a settled-marker rather than just clearing loan_at, so the fact that an
        // order was once sold on credit (and when it was cleared) survives — clearing the
        // flag would make a repaid credit sale indistinguishable from a cash one.
        //
        // Revenue is deliberately NOT affected by settling: the total was already
        // recognised when credit was given, and OrderDao#dailySummary's
        // (payment_status = 'PAID' OR loan_at IS NOT NULL) matches such a row exactly once
        // whichever side of settlement it is on.
        list.add(new Migration(21, "a fully collected loan returns to the dashboard", List.of(
            "ALTER TABLE customer_order ADD COLUMN loan_settled_at TIMESTAMPTZ",
            "ALTER TABLE customer_order ADD CONSTRAINT ck_loan_settled_implies_loan "
                + "CHECK (loan_settled_at IS NULL OR loan_at IS NOT NULL)",
            // Replaces migration 20's index: every lookup is for rows still ON the ledger,
            // which is a shrinking subset of all the loans ever given.
            "DROP INDEX IF EXISTS ix_order_loan",
            "CREATE INDEX ix_order_loan_open ON customer_order (loan_at) "
                + "WHERE loan_at IS NOT NULL AND loan_settled_at IS NULL"
        )));

        // Backfill for rows created between migration 20 landing and migration 21's
        // auto-settle logic landing in OrderDao#applyPayment: any loan that reached PAID
        // during that window has no loan_settled_at at all, so it is stuck showing
        // "Still Owed Rs 0.00" on the Pay Later ledger forever, with no way to clear it
        // (Collect demands an amount greater than zero, which a paid-up order has none of).
        // Settled "now" rather than backdated to when it actually paid off, since the exact
        // moment was never recorded for these rows.
        list.add(new Migration(22, "backfill loan_settled_at for loans that reached PAID before auto-settle existed", List.of(
            "UPDATE customer_order SET loan_settled_at = now() "
                + "WHERE loan_at IS NOT NULL AND loan_settled_at IS NULL AND payment_status = 'PAID'"
        )));

        // Rider/delivery-run tracking (migration 14) removed outright — not the DELIVERY
        // order type itself, which stays exactly as it was (customers still place
        // delivery orders with an address and a delivery fee); this drops only the
        // separate "assign a rider, dispatch a run, settle it on return" feature that
        // used to live on its own tab. Order matters: the column has to go before the
        // table it references, and delivery_run before rider for the same reason —
        // dropping a column also drops the partial index and FK that depended on it,
        // same as a table drop takes its own indexes and constraints with it.
        list.add(new Migration(23, "remove rider/delivery-run tracking", List.of(
            "ALTER TABLE customer_order DROP COLUMN delivery_run_id",
            "DROP TABLE delivery_run",
            "DROP TABLE rider"
        )));

        return list;
    }

    /**
     * Hashes any staff password still stored as plaintext. Runs inside the same
     * transaction as the SQL migrations (called from applyAll), so a failure leaves
     * every row untouched rather than half the staff locked out. Idempotent: rows
     * already in {@code pbkdf2$...} form are skipped, so re-running is a no-op.
     */
    private static void applyPasswordHashing(Connection conn) throws SQLException {
        if (isApplied(conn, 12)) return;
        int rehashed = 0;
        try (PreparedStatement select = conn.prepareStatement("SELECT id, password FROM staff");
             PreparedStatement update = conn.prepareStatement("UPDATE staff SET password = ? WHERE id = ?")) {
            try (var rs = select.executeQuery()) {
                while (rs.next()) {
                    String stored = rs.getString("password");
                    if (rps.util.PasswordHasher.isHashed(stored)) continue;
                    update.setString(1, rps.util.PasswordHasher.hash(stored == null ? "" : stored));
                    update.setInt(2, rs.getInt("id"));
                    update.addBatch();
                    rehashed++;
                }
            }
            update.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO schema_version (version, description) VALUES (?, ?)")) {
            ps.setInt(1, 12);
            ps.setString(2, "hash staff passwords with PBKDF2 (" + rehashed + " rehashed)");
            ps.executeUpdate();
        }
        System.out.println("Migration 12: rehashed " + rehashed + " staff password(s).");
    }
}
