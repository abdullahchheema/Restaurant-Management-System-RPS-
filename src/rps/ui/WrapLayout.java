package rps.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A FlowLayout that actually wraps when placed inside a JScrollPane. Plain FlowLayout
 * reports a preferred size based on a single row regardless of the container's actual
 * width, so a scroll pane around it grows sideways instead of wrapping to new rows —
 * this recomputes the preferred height for the width it's actually given, so tiles
 * wrap into a grid and the pane scrolls vertically instead.
 */
public final class WrapLayout extends FlowLayout {

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            java.awt.Container container = target;
            while (container.getSize().width == 0 && container.getParent() != null) {
                container = container.getParent();
            }
            targetWidth = container.getSize().width;
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
            int maxWidth = targetWidth - horizontalInsetsAndGap;

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            int componentCount = target.getComponentCount();
            for (int i = 0; i < componentCount; i++) {
                Component component = target.getComponent(i);
                if (!component.isVisible()) continue;

                Dimension componentSize = preferred ? component.getPreferredSize() : component.getMinimumSize();
                if (rowWidth + componentSize.width > maxWidth && rowWidth > 0) {
                    dim.width = Math.max(dim.width, rowWidth);
                    dim.height += rowHeight + vgap;
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) rowWidth += hgap;
                rowWidth += componentSize.width;
                rowHeight = Math.max(rowHeight, componentSize.height);
            }
            dim.width = Math.max(dim.width, rowWidth);
            dim.height += rowHeight + vgap;

            dim.width += horizontalInsetsAndGap;
            dim.height += insets.top + insets.bottom + (vgap * 2);
            return dim;
        }
    }
}
