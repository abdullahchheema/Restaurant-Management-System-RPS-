package rps.ui;

import rps.model.MenuItem;
import rps.model.MenuItemVariant;
import rps.model.OptionValue;
import rps.ui.icon.LineIcon;
import rps.ui.theme.Theme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Picks a size/variant and, if the item has one attached, an option value (e.g. crust
 * type) for that specific item — one row of clickable cards per choice (icon, label,
 * price for sizes; label only for options), instead of a plain JOptionPane option list
 * or two chained modals. One shared dialog for every "choose options" moment in the app
 * (POS, order editing) rather than each screen re-implementing its own picker.
 */
final class SizePickerDialog extends JDialog {

    /** The variant is always present; option is null for an item with no attached
     *  option group. */
    record Selection(MenuItemVariant variant, OptionValue option) {}

    private MenuItemVariant selectedVariant;
    private OptionValue selectedOption;
    private boolean confirmed;
    private final List<MenuItemVariant> variants;
    private final List<OptionValue> optionValues;
    private final java.util.List<SizeCard> sizeCards = new java.util.ArrayList<>();
    private final java.util.List<OptionCard> optionCards = new java.util.ArrayList<>();
    private final JButton addToCart;

    private SizePickerDialog(Component parent, MenuItem item, LineIcon icon) {
        super(SwingUtilities.getWindowAncestor(parent), "Choose options", ModalityType.APPLICATION_MODAL);
        this.variants = item.variants();
        this.selectedVariant = variants.get(0);
        this.optionValues = item.hasOptions() ? item.optionGroup().values() : List.of();

        getContentPane().setBackground(Theme.SURFACE);
        setLayout(new BorderLayout());

        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(28, 32, 24, 32));

        JPanel choices = new JPanel();
        choices.setOpaque(false);
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));

        JLabel heading = new JLabel("Choose options");
        heading.setFont(Theme.FONT_BRAND.deriveFont(Font.PLAIN, 26f));
        heading.setForeground(Theme.TEXT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        choices.add(heading);
        choices.add(Box.createVerticalStrut(6));
        JLabel subtitle = new JLabel("<html>" + escape(item.name()) + "</html>");
        subtitle.setFont(Theme.FONT_BODY.deriveFont(15f));
        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        choices.add(subtitle);
        choices.add(Box.createVerticalStrut(20));

        if (variants.size() > 1) {
            choices.add(sectionLabel("SIZE"));
            choices.add(Box.createVerticalStrut(8));
            JPanel sizeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
            sizeRow.setOpaque(false);
            sizeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (MenuItemVariant v : variants) {
                SizeCard card = new SizeCard(v, icon);
                sizeCards.add(card);
                sizeRow.add(card);
            }
            choices.add(sizeRow);
            choices.add(Box.createVerticalStrut(16));
        }

        if (!optionValues.isEmpty()) {
            choices.add(sectionLabel(item.optionGroup().name().toUpperCase(java.util.Locale.ROOT)));
            choices.add(Box.createVerticalStrut(8));
            JPanel optionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
            optionRow.setOpaque(false);
            optionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (OptionValue v : optionValues) {
                OptionCard card = new OptionCard(v);
                optionCards.add(card);
                optionRow.add(card);
            }
            choices.add(optionRow);
        }

        content.add(choices, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 16));
        bottom.setOpaque(false);
        JComponent divider = new JPanel();
        divider.setPreferredSize(new Dimension(1, 1));
        divider.setBackground(Theme.BORDER);
        divider.setOpaque(true);
        bottom.add(divider, BorderLayout.NORTH);

        addToCart = pillButton("Add to Cart");
        // Nothing is preselected for an option group — unlike size, which always starts
        // on the first variant — so the cashier can't confirm without an active choice.
        addToCart.setEnabled(optionValues.isEmpty());
        addToCart.addActionListener(e -> { confirmed = true; dispose(); });
        bottom.add(addToCart, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);

        add(content, BorderLayout.CENTER);
        refreshSelection();

        // A minimum WIDTH only — height must come from pack()'s own measurement of the
        // actual content. Forcing both dimensions (an earlier version of this fixed
        // height to 300) fights that measurement and silently wraps/clips whatever
        // doesn't fit, the same failure mode as the earlier size-editor JTable bug.
        int cardCount = Math.max(variants.size(), optionValues.size());
        setMinimumSize(new Dimension(Math.max(420, 210 + cardCount * 176), 0));
        pack();
        setLocationRelativeTo(parent);
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.FONT_SMALL_BOLD);
        l.setForeground(Theme.TEXT_MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private void refreshSelection() {
        for (SizeCard card : sizeCards) {
            card.setSelected(card.variant == selectedVariant);
        }
        for (OptionCard card : optionCards) {
            card.setSelected(card.value == selectedOption);
        }
        addToCart.setEnabled(optionValues.isEmpty() || selectedOption != null);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Shows the dialog and returns the chosen variant (and option, if the item has an
     *  attached option group), or null if closed without confirming (the window close
     *  box) — Add to Cart always confirms the currently highlighted cards. */
    static Selection pick(Component parent, MenuItem item, LineIcon icon) {
        SizePickerDialog dialog = new SizePickerDialog(parent, item, icon);
        dialog.setVisible(true);
        return dialog.confirmed ? new Selection(dialog.selectedVariant, dialog.selectedOption) : null;
    }

    private static JButton pillButton(String text) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = getHeight();
                g2.setColor(isEnabled() ? getBackground() : Theme.BORDER);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(Theme.FONT_HEADING.deriveFont(Font.BOLD, 16f));
        b.setForeground(Theme.ON_PRIMARY);
        b.setBackground(Theme.PRIMARY);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(200, 52));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { if (b.isEnabled()) { b.setBackground(Theme.PRIMARY_DARK); b.repaint(); } }
            @Override public void mouseExited(MouseEvent e) { b.setBackground(Theme.PRIMARY); b.repaint(); }
        });
        return b;
    }

    /** One clickable size option: icon, label, price — highlighted with a burgundy
     *  border and tinted background when selected, matching the locked "restrained
     *  luxury" palette rather than a stock checkbox/radio list. */
    private final class SizeCard extends JPanel {
        final MenuItemVariant variant;
        private final LineIcon icon;
        private boolean isSelected;
        private final JLabel iconLabel;
        private final JLabel nameLabel;
        private final JLabel priceLabel;

        SizeCard(MenuItemVariant variant, LineIcon icon) {
            this.variant = variant;
            this.icon = icon != null ? icon : LineIcon.CATEGORY_PIZZA;
            setLayout(new BorderLayout(0, 6));
            setPreferredSize(new Dimension(150, 150));
            setBorder(BorderFactory.createEmptyBorder(16, 10, 14, 10));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setOpaque(false);

            iconLabel = new JLabel(this.icon.of(40, Theme.TEXT_MUTED), SwingConstants.CENTER);
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            add(iconLabel, BorderLayout.NORTH);

            JPanel textCol = new JPanel();
            textCol.setOpaque(false);
            textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
            nameLabel = new JLabel(variant.sizeLabel(), SwingConstants.CENTER);
            nameLabel.setFont(Theme.FONT_BODY_BOLD.deriveFont(16f));
            nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            priceLabel = new JLabel(variant.price().format(), SwingConstants.CENTER);
            priceLabel.setFont(Theme.FONT_BODY.deriveFont(14f));
            priceLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            textCol.add(nameLabel);
            textCol.add(priceLabel);
            add(textCol, BorderLayout.CENTER);

            MouseAdapter click = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    selectedVariant = SizeCard.this.variant;
                    refreshSelection();
                }
            };
            addMouseListener(click);
            iconLabel.addMouseListener(click);
            textCol.addMouseListener(click);
            nameLabel.addMouseListener(click);
            priceLabel.addMouseListener(click);
        }

        void setSelected(boolean selected) {
            this.isSelected = selected;
            Color fg = selected ? Theme.PRIMARY : Theme.TEXT;
            Color priceFg = selected ? Theme.PRIMARY : Theme.TEXT_MUTED;
            nameLabel.setForeground(fg);
            priceLabel.setForeground(priceFg);
            iconLabel.setIcon(icon.of(40, selected ? Theme.PRIMARY : Theme.TEXT_MUTED));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isSelected ? Theme.PRIMARY_TINT : Theme.SURFACE);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.RADIUS_LG * 2, Theme.RADIUS_LG * 2);
            g2.setStroke(new BasicStroke(isSelected ? 2f : 1.3f));
            g2.setColor(isSelected ? Theme.PRIMARY : Theme.BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.RADIUS_LG * 2, Theme.RADIUS_LG * 2);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** One clickable option value — same card treatment as size, but label-only (no
     *  price, since options are descriptive-only). */
    private final class OptionCard extends JPanel {
        final OptionValue value;
        private boolean isSelected;
        private final JLabel nameLabel;

        OptionCard(OptionValue value) {
            this.value = value;
            setLayout(new BorderLayout());
            setPreferredSize(new Dimension(150, 64));
            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setOpaque(false);

            nameLabel = new JLabel(value.name(), SwingConstants.CENTER);
            nameLabel.setFont(Theme.FONT_BODY_BOLD.deriveFont(15f));
            add(nameLabel, BorderLayout.CENTER);

            MouseAdapter click = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    selectedOption = OptionCard.this.value;
                    refreshSelection();
                }
            };
            addMouseListener(click);
            nameLabel.addMouseListener(click);
        }

        void setSelected(boolean selected) {
            this.isSelected = selected;
            nameLabel.setForeground(selected ? Theme.PRIMARY : Theme.TEXT);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isSelected ? Theme.PRIMARY_TINT : Theme.SURFACE);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.RADIUS_LG * 2, Theme.RADIUS_LG * 2);
            g2.setStroke(new BasicStroke(isSelected ? 2f : 1.3f));
            g2.setColor(isSelected ? Theme.PRIMARY : Theme.BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.RADIUS_LG * 2, Theme.RADIUS_LG * 2);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
