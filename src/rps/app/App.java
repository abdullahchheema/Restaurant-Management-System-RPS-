package rps.app;

import rps.backup.OffsiteBackupService;
import rps.db.Db;
import rps.db.Migrations;
import rps.db.StaffDao;
import rps.model.Staff;
import rps.ui.LoginWindow;
import rps.ui.MainWindow;
import rps.ui.theme.Theme;
import rps.util.AppSettings;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class App {

    public static void main(String[] args) {
        rps.util.AppLog.install();
        Theme.install();
        SwingUtilities.invokeLater(App::start);
    }

    /**
     * A loop, not mutual recursion with showStartupError(). The previous form called
     * start() from inside the error dialog's "Retry" branch, which — on a genuinely bound
     * failure like the DB service still starting up right after a fresh install — could
     * recurse without limit, and registered a new shutdown hook on every successful pass
     * through (each retry added another Thread to Runtime's hook set, none of them ever
     * removed). The shutdown hook is now registered exactly once, after the loop exits
     * successfully, and "Retry" simply loops back to the top instead of re-entering.
     */
    private static void start() {
        while (true) {
            try {
                Db db = Db.get();
                Migrations.applyAll(db);

                OffsiteBackupService offsiteBackupService =
                    new OffsiteBackupService(db.connectionInfo(), rps.util.AppSettings.get().backupDir());
                Runtime.getRuntime().addShutdownHook(new Thread(offsiteBackupService::shutdown, "offsite-backup-shutdown"));

                Staff stayedSignedIn = loadStayedSignedInStaff(db);
                if (stayedSignedIn != null) {
                    new MainWindow(db, new Session(stayedSignedIn), offsiteBackupService).setVisible(true);
                } else {
                    new LoginWindow(db, offsiteBackupService).setVisible(true);
                }
                return;
            } catch (Exception e) {
                System.err.println("Startup failed: " + e.getMessage());
                e.printStackTrace(System.err);
                if (!offerRetry(e)) {
                    System.exit(1);
                }
                // else: loop back to the top and try again — nothing above this point
                // has taken effect yet on a failed pass, so retrying is safe to repeat.
            }
        }
    }

    /**
     * "Stay signed in": the till should never demand a login/password again once someone
     * has signed in, until they explicitly log out (LoginWindow / MainWindow.logout() are
     * the only two places that touch this setting). Re-reads the staff row fresh rather
     * than trusting anything cached, so a password change, role change or deactivation
     * made since the last launch takes effect immediately rather than being silently
     * bypassed by the remembered session.
     */
    private static Staff loadStayedSignedInStaff(Db db) {
        AppSettings settings = AppSettings.get();
        java.util.Optional<Integer> id = settings.stayedSignedInStaffId();
        if (id.isEmpty()) return null;

        Staff staff;
        try {
            staff = new StaffDao(db).findById(id.get());
        } catch (Exception e) {
            System.err.println("Could not restore stayed-signed-in staff: " + e.getMessage());
            return null;
        }
        if (staff != null && staff.active()) return staff;

        settings.setStayedSignedInStaffId(null);   // deleted or deactivated since last launch
        return null;
    }

    /** Returns true to retry, false to exit. Pure UI — no side effect on app state. */
    private static boolean offerRetry(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lowered = detail.toLowerCase(java.util.Locale.ROOT);
        String hint = lowered.contains("connect") || lowered.contains("refused")
            ? "\n\nPostgreSQL does not appear to be running. Start the PostgreSQL service, then try again."
            : "";
        Object[] options = {"Retry", "Exit"};
        int choice = JOptionPane.showOptionDialog(null,
            "Royal Pizza Sahowala could not start:\n" + detail + hint,
            "Startup error", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
            null, options, options[0]);
        return choice == 0;
    }
}
