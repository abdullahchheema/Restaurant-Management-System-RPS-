package rps.ui;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;

/**
 * A vertically-scrolling panel (any layout — typically a BoxLayout Y_AXIS column, but
 * not required to be) that always matches its scroll pane viewport's width. A plain
 * JPanel doesn't implement Scrollable, so a JViewport has nothing to shrink it to — if
 * any child inside (a GridLayout of a few buttons, or a single long unwrapped label)
 * computes a preferred width wider than the panel's own available width, that content —
 * and everything right-anchored past it — either gets silently clipped (if the
 * horizontal scrollbar is disabled) or pushed out past a horizontal scrollbar the
 * dialog was never meant to need (if it isn't). Forcing width-tracking makes the
 * layout actually lay children out within the real available width instead of their
 * own unclamped preferred width, so an oversized child is compressed to fit rather
 * than dragging the whole scrollable area wider — the same fix WrapPanel applies for
 * the wrapping tile/category grids.
 */
public final class ScrollableColumn extends JPanel implements Scrollable {

    public ScrollableColumn(LayoutManager layout) {
        super(layout);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 120;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
