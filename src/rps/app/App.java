package rps.app;

import rps.backup.OffsiteBackupService;
import rps.db.Db;
import rps.db.Migrations;
import rps.ui.LoginWindow;
import rps.ui.theme.Theme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class App {

    public static void main(String[] args) {
        Theme.install();
        SwingUtilities.invokeLater(App::start);
    }

    private static void start() {
        try {
            Db db = Db.get();
            Migrations.applyAll(db);

            OffsiteBackupService offsiteBackupService =
                new OffsiteBackupService(db.connectionInfo(), rps.util.AppSettings.get().backupDir());
            Runtime.getRuntime().addShutdownHook(new Thread(offsiteBackupService::shutdown, "offsite-backup-shutdown"));

            new LoginWindow(db, offsiteBackupService).setVisible(true);
        } catch (Exception e) {
            showStartupError(e);
        }
    }

    private static void showStartupError(Exception e) {
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
        if (choice == 0) {
            start();
        } else {
            System.exit(1);
        }
    }
}
