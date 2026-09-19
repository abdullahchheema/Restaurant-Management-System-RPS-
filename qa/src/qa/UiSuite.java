package qa;

import rps.app.Session;
import rps.backup.OffsiteBackupService;
import rps.db.Db;
import rps.db.StaffDao;
import rps.model.Role;
import rps.model.Staff;
import rps.ui.MainWindow;
import rps.ui.theme.Theme;
import rps.util.AppSettings;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the real main window for each role and walks every tab, forcing a full layout
 * and paint pass on each. The other suites exercise the database and the money rules but
 * never construct a single Swing component, so a panel that throws while building — a
 * missing icon, a bad GridBag constraint, an NPE on empty data — would reach the client
 * unnoticed. An uncaught-exception handler catches anything thrown on the EDT or on the
 * background workers the panels start, which would otherwise only print to stderr.
 */
public final class UiSuite {

    private static final AtomicInteger uncaught = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        System.out.println("UI SUITE — panel construction, layout and paint");

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            uncaught.incrementAndGet();
            System.out.println("  !! uncaught on " + thread.getName() + ": " + error);
            error.printStackTrace(System.out);
        });

        Theme.install();
        Db db = Db.get();
        StaffDao staffDao = new StaffDao(db);
        OffsiteBackupService backup =
            new OffsiteBackupService(db.connectionInfo(), AppSettings.get().backupDir());

        // Taken straight from the staff table rather than through authenticate(): this
        // suite is about rendering, and tying it to a particular seeded password would
        // make it fail on any database whose admin password has since been changed.
        Staff manager = staffDao.listActive().stream().filter(Staff::isManager).findFirst().orElse(null);
        QA.check("UI000", "Manager account available for the run", manager != null,
            manager == null ? "no active manager in this database" : manager.fullName());
        if (manager == null) {
            QA.summary("UI SUITE");
            System.exit(1);
        }

        // Same person, downgraded role — exercises the employee tab set without needing
        // a second seeded account.
        Staff employee = new Staff(manager.id(), "", manager.firstName(), manager.lastName(),
            Role.EMPLOYEE, true);

        // New Order, Dashboard, Pay Later (+ Menu, Staff, Reports, Settings for a
        // manager). Pay Later is deliberately visible to both roles: a cashier is the
        // one who takes the money back off a customer who owes it. Deliveries (rider
        // dispatch tracking) was removed outright, not the Delivery order TYPE itself.
        exercise("MGR", "Manager", db, new Session(manager), backup, 7);
        exercise("EMP", "Employee", db, new Session(employee), backup, 3);

        QA.check("UI900", "No uncaught exceptions anywhere in the UI", uncaught.get() == 0,
            uncaught.get() + " uncaught");

        QA.summary("UI SUITE");
        System.exit(QA.failed > 0 || uncaught.get() > 0 ? 1 : 0);
    }

    private static void exercise(String idPrefix, String label, Db db, Session session,
                                 OffsiteBackupService backup, int expectedTabs) throws Exception {
        QA.section(label + " session");
        MainWindow[] window = new MainWindow[1];

        SwingUtilities.invokeAndWait(() -> window[0] = new MainWindow(db, session, backup));
        QA.check(idPrefix + "001", label + ": main window constructs", window[0] != null, "");

        SwingUtilities.invokeAndWait(() -> {
            window[0].setVisible(true);
            window[0].setSize(1200, 800);
        });
        QA.check(idPrefix + "002", label + ": window realises and lays out", window[0].isShowing(), "");

        int count = window[0].screenCount();
        QA.check(idPrefix + "004", label + ": role sees the right screens",
            count == expectedTabs, "expected " + expectedTabs + ", got " + count);

        for (int i = 0; i < count; i++) {
            final int index = i;
            SwingUtilities.invokeAndWait(() -> window[0].selectScreen(index));
            Thread.sleep(700);   // let each panel's initial background load finish
            String name = window[0].screenNameAt(index);
            Component comp = window[0].screenComponentAt(index);
            SwingUtilities.invokeAndWait(() -> paintOffscreen(comp));
            QA.check(idPrefix + "1" + String.format("%02d", i),
                label + ": screen \"" + name + "\" renders", true, "");
        }

        // The declared minimum size is where layout managers break if they are going to.
        SwingUtilities.invokeAndWait(() -> window[0].setSize(1000, 650));
        Thread.sleep(400);
        QA.check(idPrefix + "800", label + ": survives the minimum window size", uncaught.get() == 0, "");

        SwingUtilities.invokeAndWait(window[0]::dispose);
        Thread.sleep(300);
    }

    private static void paintOffscreen(Component component) {
        component.setSize(1200, 700);
        component.doLayout();
        BufferedImage image = new BufferedImage(1200, 700, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            component.paint(g);
        } finally {
            g.dispose();
        }
    }

}
