package rps.ui;

import rps.app.Session;
import rps.backup.OffsiteBackupService;
import rps.db.Db;
import rps.print.ReceiptPrinter;
import rps.ui.icon.LineIcon;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The main application window. Only ever constructed with a non-null Session (see
 * LoginWindow) — this is the structural half of login enforcement. The cosmetic half
 * (which screens exist at all) also follows the session: manager-only screens are not
 * merely disabled, they are never added to the sidebar for a non-manager session.
 *
 * <p>Navigation is a left icon sidebar (one button per screen, screens shown via
 * CardLayout) rather than a top JTabbedPane — a deliberate layout choice matching the
 * reference the shop was built to, not a functional change: exactly the same set of
 * screens exists, gated by the same role check as before.
 */
public final class MainWindow extends JFrame {

    private static final int SIDEBAR_WIDTH = 76;

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

    /** One entry per screen the current session can see, in sidebar order. */
    private record ScreenEntry(String name, LineIcon icon, String cardKey, JComponent component) {}

    private final List<ScreenEntry> screens = new ArrayList<>();
    private final List<SidebarButton> sidebarButtons = new ArrayList<>();
    private final CardLayout contentCards = new CardLayout();
    private final JPanel contentHolder = new JPanel(contentCards);

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

        JComponent content = buildContent();   // populates `screens` — must run before buildSidebar()
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(buildSidebar(), BorderLayout.WEST);
        body.add(content, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

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
        printerBox.add(new JLabel(LineIcon.PRINT.of(16, Theme.TEXT_MUTED)));
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

        JLabel avatar = new JLabel(LineIcon.PERSON.of(22, Theme.TEXT_MUTED));
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
        logout.setIcon(LineIcon.LOGOUT.of(15, Theme.TEXT_MUTED));
        logout.setIconTextGap(8);
        logout.addActionListener(e -> logout());
        right.add(logout);
        header.add(right, BorderLayout.EAST);

        return header;
    }

    /** Populates {@code screens} and the CardLayout content panel. Must run before
     *  {@link #buildSidebar()}, which reads the same list to build one button per entry. */
    private JComponent buildContent() {
        contentHolder.setOpaque(false);

        addScreen("New Order", LineIcon.CART, new PosPanel(db, session, offsiteBackupService));
        addScreen("Dashboard", LineIcon.DASHBOARD, new DashboardPanel(db, session));
        // Not manager-only: whoever is at the counter is the one who takes the money back
        // off a customer who owes it, so a cashier has to be able to see and settle a debt.
        addScreen("Pay Later", LineIcon.WALLET, new LoanPanel(db, session));

        // Manager-only screens are never added for a non-manager session — not disabled, absent.
        if (session.isManager()) {
            addScreen("Menu", LineIcon.LIST, new MenuAdminPanel(db, session));
            addScreen("Staff", LineIcon.TEAM, new StaffAdminPanel(db, session));
            addScreen("Reports", LineIcon.CHART, new ReportsPanel(db, session));
            addScreen("Settings", LineIcon.GEAR, new SettingsPanel(db, session, offsiteBackupService));
        }

        return contentHolder;
    }

    private void addScreen(String name, LineIcon icon, JComponent panel) {
        String key = "screen-" + screens.size();
        screens.add(new ScreenEntry(name, icon, key, panel));
        contentHolder.add(panel, key);
    }

    private JComponent buildSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(Theme.PRIMARY_DARK);
        sidebar.setBorder(BorderFactory.createEmptyBorder(20, 0, 20, 0));
        sidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));

        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < screens.size(); i++) {
            ScreenEntry entry = screens.get(i);
            SidebarButton btn = new SidebarButton(entry.icon(), entry.name());
            final int index = i;
            btn.addActionListener(e -> selectScreen(index));
            group.add(btn);
            sidebarButtons.add(btn);
            btn.setAlignmentX(Component.CENTER_ALIGNMENT);
            sidebar.add(btn);
            sidebar.add(Box.createVerticalStrut(6));
        }
        if (!sidebarButtons.isEmpty()) sidebarButtons.get(0).setSelected(true);
        sidebar.add(Box.createVerticalGlue());
        return sidebar;
    }

    /** Icon-only nav button: a translucent icon at rest, full white plus a rounded
     *  highlight and a gold accent tab on the left edge when selected — the sidebar's own
     *  dark ground is what the translucency is tuned against, so this stays private to it
     *  rather than living in UiFactory alongside the light-surface widgets. */
    private static final class SidebarButton extends JToggleButton {
        private static final Color DIM_ICON = new Color(0xFF, 0xFF, 0xFF, 0xA8);

        SidebarButton(LineIcon icon, String tooltip) {
            setToolTipText(tooltip);
            setIcon(icon.of(22, DIM_ICON));
            setSelectedIcon(icon.of(22, Theme.ON_PRIMARY));
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setHorizontalAlignment(SwingConstants.CENTER);
            Dimension size = new Dimension(MainWindow.SIDEBAR_WIDTH, 52);
            setPreferredSize(size);
            setMaximumSize(size);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (isSelected()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.PRIMARY);
                int pad = 10;
                g2.fillRoundRect(pad, 2, getWidth() - pad * 2, getHeight() - 4, 12, 12);
                g2.setColor(Theme.ACCENT);
                g2.fillRoundRect(0, 12, 4, getHeight() - 24, 4, 4);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    // ---------------------------------------------------------------- test/navigation hooks

    /** How many screens this session's sidebar shows — for the UI test suite, which has
     *  no JTabbedPane to introspect any more. */
    public int screenCount() {
        return screens.size();
    }

    public String screenNameAt(int index) {
        return screens.get(index).name();
    }

    public Component screenComponentAt(int index) {
        return screens.get(index).component();
    }

    /** Switches the CardLayout to the given screen and reflects that in the sidebar's own
     *  selection state — used by sidebar clicks and by the UI test suite driving navigation
     *  without simulating a real mouse event. */
    public void selectScreen(int index) {
        contentCards.show(contentHolder, screens.get(index).cardKey());
        if (!sidebarButtons.get(index).isSelected()) {
            sidebarButtons.get(index).setSelected(true);
        }
    }

    private void logout() {
        rps.util.AppSettings.get().setStayedSignedInStaffId(null);
        connectionTimer.stop();
        dispose();
        SwingUtilities.invokeLater(() -> new LoginWindow(db, offsiteBackupService).setVisible(true));
    }
}
