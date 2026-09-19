package rps.ui.theme;

import rps.ui.TouchScroll;

import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * A rounded, elevated panel with a soft, restrained two-layer shadow — FlatLaf only
 * paints elevation for popups and internal frames, so any other "raised surface" has
 * to paint its own. Deliberately subtle (low alpha, small offset, no blur halo) per
 * the locked "minimalist luxury" direction: no glow, no glassmorphism.
 */
public class Card extends JPanel {

    private static final int RADIUS = Theme.RADIUS_LG;
    private static final Color SHADOW_FAR = new Color(0x21, 0x1F, 0x1C, 18);
    private static final Color SHADOW_NEAR = new Color(0x21, 0x1F, 0x1C, 28);

    public Card() {
        this(new BorderLayout());
    }

    public Card(java.awt.LayoutManager layout) {
        super(layout);
        setOpaque(false);
        setBorder(new EmptyBorder(16, 16, 16, 16));
        // A Card is opaque-looking and normally fills most of whatever container it sits
        // in, so on a touchscreen till it is most of the actual surface a finger lands
        // on — wiring drag-to-scroll only on the THIN gaps around cards (as every screen
        // built on top of Card used to do) left the card bodies themselves dead to touch,
        // which is most of the screen. One installation here covers every Card in the
        // app at once. Harmless where a caller also wires its own more specific tap
        // action on the same instance (MenuAdminPanel's item cards) — this is a no-op
        // tap, so it just adds a second (redundant, not
        // conflicting) drag listener alongside it.
        TouchScroll.install(this);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        g2.setColor(SHADOW_FAR);
        g2.fill(new RoundRectangle2D.Float(1, 3, w - 2, h - 4, RADIUS, RADIUS));
        g2.setColor(SHADOW_NEAR);
        g2.fill(new RoundRectangle2D.Float(1, 2, w - 2, h - 4, RADIUS, RADIUS));

        g2.setColor(Theme.SURFACE);
        g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 3, RADIUS, RADIUS));

        g2.setColor(Theme.BORDER);
        g2.draw(new RoundRectangle2D.Float(0, 0, w - 1.5f, h - 3.5f, RADIUS, RADIUS));

        g2.dispose();
        super.paintComponent(g);
    }
}
