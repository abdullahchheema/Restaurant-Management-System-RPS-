package rps.app;

import rps.db.DatabaseException;
import rps.db.Db;
import rps.db.Migrations;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Headless, non-interactive database setup — no Swing, no window, no user prompt.
 *
 * <p>Exists for the installer, which runs this once, at install/upgrade time, with the
 * bundled JRE, so that by the time the client double-clicks the desktop shortcut the
 * schema is already built, the real menu is already seeded, and a manager account already
 * exists — rather than deferring all of that to whatever staff member happens to launch
 * the app first (which is what App.start() already does today for a developer running
 * run.ps1, and continues to do; this class does not replace that path, it only lets the
 * installer run the same one ahead of time and fail loudly if it didn't work).
 *
 * <p>Exit codes are the installer's contract: 0 means the database is ready to serve the
 * app; anything else means the installer must stop and show its own failure page rather
 * than silently declaring success over a database that will not actually work.
 */
public final class Bootstrap {

    private Bootstrap() {}

    public static void main(String[] args) {
        rps.util.AppLog.install();
        System.out.println("Bootstrap: applying schema migrations...");
        try {
            Db db = Db.get();
            Migrations.applyAll(db);
            verify(db);
            System.out.println("Bootstrap: database ready.");
            System.exit(0);
        } catch (DatabaseException e) {
            System.err.println("Bootstrap failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Bootstrap failed with an unexpected error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    /**
     * Confirms the database is actually usable, not merely that the migration statements
     * executed without throwing — the distinction matters because migrations run inside
     * one transaction (Migrations.applyAll) and a bug that silently produced zero rows
     * where seed data was expected would otherwise report success. Checked against
     * exactly what the app needs to function on first login: a manager account to sign in
     * with, and a non-empty menu to sell from.
     */
    private static void verify(Db db) throws DatabaseException {
        long managers = scalar(db, "SELECT count(*) FROM staff WHERE role = 'MANAGER' AND active = TRUE");
        if (managers < 1) {
            throw new DatabaseException("Verification failed: no active manager account exists after migration.");
        }
        long items = scalar(db, "SELECT count(*) FROM menu_item WHERE deleted_at IS NULL AND available = TRUE");
        if (items < 1) {
            throw new DatabaseException("Verification failed: menu is empty after migration.");
        }
        System.out.println("Bootstrap: verified " + managers + " manager account(s), " + items + " menu item(s).");
    }

    private static long scalar(Db db, String sql) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }
}
