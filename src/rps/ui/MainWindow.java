package rps.ui;

import rps.app.Session;
import rps.backup.OffsiteBackupService;
import rps.db.Db;
import rps.print.ReceiptPrinter;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;

import javax.swing.*;
import java.awt.*;

/**
 * The main application window. Only ever constructed with a non-null Session (see
 * LoginWindow) — this is the structural half of login enforcement. The cosmetic half
 * (which tabs exist at all) also follows the session: manager-only screens are not
 * merely disabled, they are never added to the tab strip for a non-manager session.
 */
public final class MainWindow extends JFrame {

    private final Db db;
    private final Session session;
    private final OffsiteBackupService offsiteBackupService;
    private final JLabel connectionDot = new JLabel("●");
    private final JLabel connectionLabel = UiFactory.muted("Connected");
    private final Timer connectionTimer;

    // Persistent, always-visible status — not a one-shot dismissible warning — because a
    // disconnected printer is easy to miss otherwise: nothing about it surfaces until a
    // cashier actually confirms an order and the print silently fails. This checks proactively,
    // before that ever happens, the same way the DB connection dot already does.
    private final JLabel printerDot = new JLabel("●");
    private final JLabel printerLabel = UiFactory.muted("Printer ready");
    private final Timer printerTimer;

    // Print/backup run fire-and-forget on background threads and previously reported
    // failures to System.err only — a cashier would see "Order Confirmed" with no idea
    // the kitchen ticket never printed. This chip surfaces that state without touching
    // how printing or backup actually run.
    private final JLabel warningChip = warningChipStyled();
    private String lastWarning;

    public MainWindow(Db db, Session session, OffsiteBackupService offsiteBackupService) {
        super("Royal Pizza Sahowala");
        this.db = db;
        this.session = session;
        this.offsiteBackupService = offsiteBackupService;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1200, 800);
        setMinimumSize(new Dimension(1000, 650));
        setLocationRelativeTo(null);

        setLayout(new BorderLayout());
        add(buildHeader(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);

        // Cheap liveness poll — this is what surfaces a lost database connection to the
        // user instead of the dashboard just quietly going stale with no explanation.
        connectionTimer = new Timer(5000, e -> updateConnectionIndicator());
        connectionTimer.start();
        updateConnectionIndicator();

        // Printer status changes far less often than a DB heartbeat, and the check itself
        // (OS printer enumeration + attribute query) can involve a driver round-trip, so
        // this polls less frequently and always off the EDT.
        printerTimer = new Timer(8000, e -> pollPrinterStatus());
        printerTimer.start();
        pollPrinterStatus();

        ReceiptPrinter.setOnFailure(this::showWarning);
        offsiteBackupService.setOnFailure(this::showWarning);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                connectionTimer.stop();
                printerTimer.stop();
            }
        });
    }

    private void updateConnectionIndicator() {
        boolean ok = db.isReachable();
        connectionDot.setForeground(ok ? Theme.STATUS_COMPLETED : Theme.STATUS_CANCELLED);
        connectionLabel.setText(ok ? "Connected" : "Database offline");
    }

    private void pollPrinterStatus() {
        new SwingWorker<java.util.Optional<String>, Void>() {
            @Override
            protected java.util.Optional<String> doInBackground() {
                return ReceiptPrinter.checkPrinterProblem();
            }

            @Override
            protected void done() {
                // Nothing to show when the shop has silent printing turned off entirely —
                // "Printer offline" would be misleading (nothing is being attempted), and
                // checkPrinterProblem()'s empty result can't distinguish "off by choice"
                // from "everything's fine" on its own.
                if (!rps.util.AppSettings.get().isSilentPrintingEnabled()) {
                    printerDot.setVisible(false);
                    printerLabel.setVisible(false);
                    return;
                }
                java.util.Optional<String> problem;
                try {
                    problem = get();
                } catch (Exception ex) {
                    return; // transient check failure — leave the indicator as it was
                }
                boolean ok = problem.isEmpty();
                printerDot.setVisible(true);
                printerLabel.setVisible(true);
                printerDot.setForeground(ok ? Theme.STATUS_COMPLETED : Theme.STATUS_CANCELLED);
                printerLabel.setText(ok ? "Printer ready" : "Printer offline");
                printerLabel.setToolTipText(problem.orElse(null));
                printerDot.setToolTipText(problem.orElse(null));
            }
        }.execute();
    }

    private static JLabel warningChipStyled() {
        JLabel l = new JLabel("⚠ Attention needed", SwingConstants.LEFT);
        l.setOpaque(true);
        l.setVisible(false);
        l.setFont(Theme.FONT_SMALL_BOLD);
        l.setForeground(Theme.STATUS_CANCELLED);
        l.setBackground(Theme.STATUS_CANCELLED_TINT);
        l.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.STATUS_CANCELLED, 1, true),
            BorderFactory.createEmptyBorder(3, 11, 3, 11)));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return l;
    }

    /** Called from a background thread (printer/backup executors) — must hop to the
     *  EDT before touching any Swing component. */
    private void showWarning(String message) {
        SwingUtilities.invokeLater(() -> {
            lastWarning = message;
            warningChip.setToolTipText(message);
            warningChip.setVisible(true);
        });
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.SURFACE);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 10));
        left.setOpaque(false);
        left.add(new BrandLogo(42));
        JLabel title = UiFactory.brand("Royal Pizza Sahowala");
        left.add(title);
        header.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
        right.setOpaque(false);

        warningChip.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                JOptionPane.showMessageDialog(MainWindow.this, lastWarning, "Attention needed",
                    JOptionPane.WARNING_MESSAGE);
                warningChip.setVisible(false);
            }
        });
        right.add(warningChip);

        JPanel printerBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        printerBox.setOpaque(false);
        printerBox.add(new JLabel(rps.ui.icon.LineIcon.PRINT.of(16, Theme.TEXT_MUTED)));
        printerDot.setFont(Theme.FONT_SMALL);
        printerBox.add(printerDot);
        printerBox.add(printerLabel);
        right.add(printerBox);

        JPanel statusBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        statusBox.setOpaque(false);
        connectionDot.setFont(Theme.FONT_SMALL);
        statusBox.add(connectionDot);
        statusBox.add(connectionLabel);
        right.add(statusBox);

        JLabel avatar = new JLabel(rps.ui.icon.LineIcon.PERSON.of(22, Theme.TEXT_MUTED));
        right.add(avatar);

        JPanel whoBox = new JPanel();
        whoBox.setOpaque(false);
        whoBox.setLayout(new BoxLayout(whoBox, BoxLayout.Y_AXIS));
        JLabel name = UiFactory.labelBold(session.staffName());
        name.setAlignmentX(Component.RIGHT_ALIGNMENT);
        JLabel role = UiFactory.muted(session.role() == rps.model.Role.MANAGER ? "Manager" : "Employee");
        role.setAlignmentX(Component.RIGHT_ALIGNMENT);
        whoBox.add(name);
        whoBox.add(role);
        right.add(whoBox);
        JButton logout = UiFactory.secondaryButton("Logout");
        logout.setIcon(rps.ui.icon.LineIcon.LOGOUT.of(15, Theme.TEXT_MUTED));
        logout.setIconTextGap(8);
        logout.addActionListener(e -> logout());
        right.add(logout);
        header.add(right, BorderLayout.EAST);

        return header;
    }

    private JComponent buildTabs() {
        // Font set once in Theme.install() via UIManager — not re-set here to avoid
        // two places that can silently diverge.
        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab("New Order", new PosPanel(db, session, offsiteBackupService));
        tabs.addTab("Dashboard", new DashboardPanel(db, session));
        tabs.addTab("Deliveries", new DeliveryPanel(db, session));

        // Manager-only tabs are never added for a non-manager session — not disabled, absent.
        if (session.isManager()) {
            tabs.addTab("Menu", new MenuAdminPanel(db, session));
            tabs.addTab("Staff", new StaffAdminPanel(db, session));
            tabs.addTab("Reports", new ReportsPanel(db, session));
            tabs.addTab("Settings", new SettingsPanel(db, session, offsiteBackupService));
        }

        return tabs;
    }

    private void logout() {
        connectionTimer.stop();
        dispose();
        SwingUtilities.invokeLater(() -> new LoginWindow(db, offsiteBackupService).setVisible(true));
    }
}
