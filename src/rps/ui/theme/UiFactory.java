package rps.ui.theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Small factory of consistently-themed widgets so no screen invents its own styling. */
public final class UiFactory {

    private UiFactory() {}

    public static JButton primaryButton(String text) {
        JButton b = new JButton(text);
        b.setFont(Theme.FONT_BODY_BOLD);
        b.setBackground(Theme.PRIMARY);
        b.setForeground(Theme.ON_PRIMARY);
        b.setFocusPainted(false);
        // No custom Border here — an EmptyBorder would replace FlatLaf's own painted,
        // rounded border and discard Button.arc entirely (buttons rendered as sharp
        // rectangles). Margin supplies the padding without touching border painting.
        b.setMargin(new Insets(10, 22, 10, 22));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addPressState(b, Theme.PRIMARY, Theme.PRIMARY_DARK);
        return b;
    }

    public static JButton secondaryButton(String text) {
        JButton b = new JButton(text);
        b.setFont(Theme.FONT_BODY);
        b.setBackground(Theme.SURFACE);
        b.setForeground(Theme.TEXT);
        b.setFocusPainted(false);
        b.setMargin(new Insets(9, 19, 9, 19));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addPressState(b, Theme.SURFACE, Theme.SURFACE_HOVER);
        return b;
    }

    /** Hover + pressed feedback in one listener: hover shows `hover`, a press darkens
     *  toward `hover` again (visually a second step) and always releases back to `normal`. */
    private static void addPressState(JButton b, Color normal, Color hover) {
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { if (b.isEnabled()) b.setBackground(hover); }
            @Override public void mouseExited(MouseEvent e) { if (!b.getModel().isPressed()) b.setBackground(normal); }
            @Override public void mousePressed(MouseEvent e) { if (b.isEnabled()) b.setBackground(hover); }
            @Override public void mouseReleased(MouseEvent e) {
                b.setBackground(b.getModel().isRollover() ? hover : normal);
            }
        });
    }

    public static JLabel brand(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.FONT_BRAND);
        l.setForeground(Theme.PRIMARY);
        return l;
    }

    public static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.FONT_TITLE);
        l.setForeground(Theme.TEXT);
        return l;
    }

    public static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.FONT_HEADING);
        l.setForeground(Theme.TEXT);
        return l;
    }

    public static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.FONT_BODY);
        l.setForeground(Theme.TEXT);
        return l;
    }

    public static JLabel labelBold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.FONT_BODY_BOLD);
        l.setForeground(Theme.TEXT);
        return l;
    }

    public static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.FONT_SMALL);
        l.setForeground(Theme.TEXT_MUTED);
        return l;
    }

    public static JTextField textField(int columns) {
        JTextField f = new JTextField(columns);
        styleField(f);
        return f;
    }

    public static JPasswordField passwordField(int columns) {
        JPasswordField f = new JPasswordField(columns);
        styleField(f);
        return f;
    }

    private static void styleField(JTextField f) {
        f.setFont(Theme.FONT_BODY);
        f.setPreferredSize(new Dimension(f.getPreferredSize().width, 38));
        applyFieldBorder(f, Theme.BORDER, 1);
        installFocusRing(f);
    }

    /** A visible gold ring on focus — ACCENT_DEEP, since nearly every field sits on a
     *  light (Surface/Background) ground where plain ACCENT falls under the 3:1 floor. */
    private static void installFocusRing(JTextField f) {
        f.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { applyFieldBorder(f, Theme.ACCENT_DEEP, 2); }
            @Override public void focusLost(FocusEvent e) { applyFieldBorder(f, Theme.BORDER, 1); }
        });
    }

    private static void applyFieldBorder(JTextField f, Color lineColor, int thickness) {
        int pad = 7 - thickness;
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(lineColor, thickness, true),
            new EmptyBorder(pad, pad + 3, pad, pad + 3)));
    }

    /** Marks a field invalid: red border + inline message label updated separately. */
    public static void markInvalid(JTextField field) {
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.STATUS_CANCELLED, 2, true),
            new EmptyBorder(5, 9, 5, 9)));
    }

    public static void markValid(JTextField field) {
        applyFieldBorder(field, Theme.BORDER, 1);
    }

    public static JLabel errorText(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.FONT_SMALL);
        l.setForeground(Theme.STATUS_CANCELLED);
        return l;
    }

    public static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.BORDER, 1, true),
            new EmptyBorder(12, 12, 12, 12));
    }

    /** Border for a clickable tile (menu grid, etc.) — shared so screens don't each
     *  hand-roll the same compound border with a different line color. */
    public static Border tileBorder(Color lineColor) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(lineColor, 1, true),
            BorderFactory.createEmptyBorder(12, 12, 12, 12));
    }

    /** Small round icon-style button used for quantity steppers (+/−/×) — one shared
     *  implementation instead of each screen hand-rolling its own JButton look. */
    public static JButton stepperButton(String symbol) {
        JButton b = new JButton(symbol);
        b.setFont(Theme.FONT_BODY_BOLD);
        b.setFocusPainted(false);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setPreferredSize(new Dimension(26, 26));
        b.setBackground(Theme.BACKGROUND);
        b.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1, true));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addPressState(b, Theme.BACKGROUND, Theme.SURFACE_HOVER);
        return b;
    }

    /**
     * A pill-shaped toggle used for category strips and order-type selectors —
     * one styling implementation shared everywhere instead of each screen
     * hand-rolling its own JToggleButton look.
     */
    public static JToggleButton chipToggle(String text, boolean selected) {
        return chipToggle(text, selected, null);
    }

    /** Chip with a leading icon that re-tints between TEXT_MUTED (at rest) and ON_PRIMARY
     *  (selected) — the same two states the chip's own text and fill already switch on. */
    public static JToggleButton chipToggle(String text, boolean selected, rps.ui.icon.LineIcon icon) {
        JToggleButton btn = new JToggleButton(text);
        btn.setFont(Theme.FONT_BODY);
        btn.setSelected(selected);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (icon != null) {
            btn.setIconTextGap(8);
            btn.setIcon(icon.of(15, Theme.TEXT_MUTED));
            btn.setSelectedIcon(icon.of(15, Theme.ON_PRIMARY));
        }
        applyChipStyle(btn);
        btn.addChangeListener(e -> applyChipStyle(btn));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (!btn.isSelected()) btn.setBackground(Theme.SURFACE_HOVER);
            }
            @Override public void mouseExited(MouseEvent e) { applyChipStyle(btn); }
        });
        return btn;
    }

    private static void applyChipStyle(JToggleButton btn) {
        boolean selected = btn.isSelected();
        btn.setBackground(selected ? Theme.PRIMARY : Theme.SURFACE);
        btn.setForeground(selected ? Theme.ON_PRIMARY : Theme.TEXT);
        btn.setFont(selected ? Theme.FONT_BODY_BOLD : Theme.FONT_BODY);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(selected ? Theme.PRIMARY : Theme.BORDER, 1, true),
            BorderFactory.createEmptyBorder(8, 16, 8, 16)));
    }

    /** Low-saturation tinted tag: colored text + hairline border on a tinted ground,
     *  not a solid fill — the locked, more restrained status treatment. */
    public static JLabel statusPill(rps.model.PaymentStatus status) {
        Color color = Theme.statusColor(status);
        rps.ui.icon.LineIcon icon = switch (status) {
            case UNPAID, PARTIALLY_PAID -> rps.ui.icon.LineIcon.STATUS_PENDING;
            case PAID -> rps.ui.icon.LineIcon.STATUS_COMPLETED;
        };
        return pill(status.label(), icon, color, Theme.statusTint(status));
    }

    /** The kitchen/counter track — same visual treatment, different vocabulary, so the two
     *  pills sit side by side on the dashboard without either being mistaken for the other. */
    public static JLabel fulfilmentPill(rps.model.FulfilmentStatus status) {
        Color color = Theme.fulfilmentColor(status);
        rps.ui.icon.LineIcon icon = switch (status) {
            case PENDING -> rps.ui.icon.LineIcon.STATUS_PENDING;
            case COMPLETED -> rps.ui.icon.LineIcon.STATUS_COMPLETED;
            case CANCELLED -> rps.ui.icon.LineIcon.STATUS_CANCELLED;
        };
        return pill(status.label(), icon, color, Theme.fulfilmentTint(status));
    }

    private static JLabel pill(String text, rps.ui.icon.LineIcon icon, Color color, Color tint) {
        JLabel l = new JLabel(text, icon.of(11, color), SwingConstants.CENTER);
        l.setIconTextGap(5);
        l.setOpaque(true);
        l.setBackground(tint);
        l.setForeground(color);
        l.setFont(Theme.FONT_SMALL_BOLD);
        l.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(color, 1, true),
            new EmptyBorder(3, 11, 3, 11)));
        return l;
    }

    /** Renders an order-status table column as a coloured pill instead of plain text. The
     *  pill is wrapped in a centering panel rather than stretched/aligned as the cell's
     *  own component — a JLabel's opaque background paints across its FULL bounds, so
     *  without the wrapper the tinted background would span the whole column width
     *  instead of staying a tight pill, regardless of any horizontal-alignment setting. */
    public static DefaultTableCellRenderer statusPillCellRenderer() {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                             boolean hasFocus, int row, int column) {
                JLabel base = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value instanceof rps.model.PaymentStatus || value instanceof rps.model.FulfilmentStatus) {
                    JLabel pill = value instanceof rps.model.PaymentStatus p
                        ? statusPill(p)
                        : fulfilmentPill((rps.model.FulfilmentStatus) value);
                    if (isSelected) {
                        pill.setBackground(Theme.PRIMARY_TINT);
                    }
                    java.awt.FlowLayout centerLayout = new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0);
                    javax.swing.JPanel wrap = new javax.swing.JPanel(centerLayout);
                    wrap.setOpaque(true);
                    wrap.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                    wrap.add(pill);
                    return wrap;
                }
                base.setFont(Theme.FONT_BODY);
                base.setHorizontalAlignment(SwingConstants.CENTER);
                return base;
            }
        };
    }

    /** Centers text in a table cell — used for every column except the status pill,
     *  which handles its own centering (see statusPillCellRenderer). Always renders as
     *  NOT focused: these tables act on a row via click (a button column, a clickable
     *  status pill, a whole-row detail view) rather than keyboard cell navigation, so
     *  the lead-selected-cell focus rectangle DefaultTableCellRenderer draws by default
     *  read as a stray highlighted box around whichever cell was last clicked, not as
     *  anything meaningful — passing hasFocus=false suppresses it unconditionally. */
    public static DefaultTableCellRenderer centeredCellRenderer() {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                             boolean hasFocus, int row, int column) {
                return super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            }
        };
        r.setHorizontalAlignment(SwingConstants.CENTER);
        return r;
    }
}
