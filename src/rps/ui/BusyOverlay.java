package rps.ui;

import rps.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.MouseAdapter;

/**
 * Translucent, input-blocking, ref-counted glass pane. Blocking input (not just disabling
 * one button) is what makes a double-submit structurally impossible rather than relying
 * on remembering to disable every control an async operation touches.
 */
final class BusyOverlay extends JComponent {

    private int refCount;
    private final JLabel label = new JLabel();
    private final JProgressBar bar = new JProgressBar();

    private BusyOverlay() {
        setOpaque(false);
        setLayout(new GridBagLayout());
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(180, 6));
        label.setFont(Theme.FONT_BODY_BOLD);
        label.setForeground(Theme.TEXT);

        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBackground(Theme.SURFACE);
        card.setBorder(BorderFactory.createEmptyBorder(20, 28, 20, 28));
        card.add(label, BorderLayout.NORTH);
        card.add(bar, BorderLayout.CENTER);
        add(card);

        MouseAdapter sink = new MouseAdapter() {};
        addMouseListener(sink);
        addMouseMotionListener(sink);
        addMouseWheelListener(e -> {});
        setFocusTraversalKeysEnabled(false);
        addKeyListener(new KeyAdapter() {});
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        setVisible(false);
    }

    /** Null if `owner` isn't attached to a window yet (e.g. called from a constructor
     *  before the component is added anywhere) — callers must handle that case rather
     *  than crash, since a busy indicator is cosmetic and never load-bearing. */
    static BusyOverlay forComponent(JComponent owner) {
        JRootPane root = SwingUtilities.getRootPane(owner);
        if (root == null) return null;
        if (!(root.getGlassPane() instanceof BusyOverlay)) {
            root.setGlassPane(new BusyOverlay());
        }
        return (BusyOverlay) root.getGlassPane();
    }

    void acquire(String message) {
        if (refCount++ == 0) {
            label.setText(message);
            setVisible(true);
            requestFocusInWindow();
        } else {
            label.setText(message);
        }
    }

    void release() {
        if (--refCount <= 0) {
            refCount = 0;
            setVisible(false);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(0, 0, 0, 90));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
    }
}
