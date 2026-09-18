package rps.ui.theme;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Dimension;

/**
 * Centered "nothing here" panel — an icon, a headline, and an optional muted hint —
 * shown in place of a table/list when it has no rows, instead of leaving a blank
 * rectangle with no explanation of why.
 */
public final class EmptyState extends JComponent {

    private final JLabel headlineLabel;
    private final JLabel hintLabel;

    public EmptyState(rps.ui.icon.LineIcon icon, String headline, String hint) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(48, 24, 48, 24));

        JLabel iconLabel = new JLabel(icon.of(36, Theme.TEXT_MUTED));
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(iconLabel);

        add(Box.createVerticalStrut(Theme.SPACE_MD));

        headlineLabel = UiFactory.labelBold(headline);
        headlineLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        headlineLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(headlineLabel);

        add(Box.createVerticalStrut(Theme.SPACE_XS));
        hintLabel = UiFactory.muted(hint == null ? " " : hint);
        hintLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        hintLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(hintLabel);
    }

    /** Lets a screen distinguish "nothing exists yet" from "nothing matches the current
     *  filter" without building a second EmptyState instance. */
    public void setMessage(String headline, String hint) {
        headlineLabel.setText(headline);
        hintLabel.setText(hint == null ? " " : hint);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
