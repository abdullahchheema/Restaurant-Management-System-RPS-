package rps.ui;

import rps.db.OrderDao;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A Shopify-style date-range control: a single trigger button showing the current range,
 * opening a popup with one-click presets (Last 7 Days, This Month, Last Quarter, ...) on
 * the left and a clickable calendar for a custom range on the right — replacing two plain
 * "type a date" text fields. Shared by Dashboard and Reports so both pick up the same
 * behavior and can't drift into two different date-picking experiences.
 */
final class DateRangePicker extends JPanel {

    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);

    /** Lower bound for the "All Time" preset. A fixed sentinel rather than a
     *  MIN(business_date) lookup, so this stays a pure UI component with no database
     *  dependency; it simply predates any order this shop could hold. */
    private static final LocalDate ALL_TIME_START = LocalDate.of(2000, 1, 1);

    private LocalDate from;
    private LocalDate to;
    private final JButton trigger;
    private BiConsumer<LocalDate, LocalDate> onApply = (f, t) -> {};

    private YearMonth viewMonth;
    private LocalDate pendingStart;
    private LocalDate pendingEnd;
    private JPanel calendarGrid;
    private JLabel monthLabel;
    private JButton applyButton;

    DateRangePicker(LocalDate initialFrom, LocalDate initialTo) {
        this.from = initialFrom;
        this.to = initialTo;

        setOpaque(false);
        setLayout(new BorderLayout());

        trigger = new JButton();
        trigger.setIcon(calendarIcon());
        trigger.setIconTextGap(8);
        trigger.setFont(Theme.FONT_BODY);
        trigger.setForeground(Theme.TEXT);
        trigger.setBackground(Theme.SURFACE);
        trigger.setFocusPainted(false);
        trigger.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.BORDER, 1, true),
            BorderFactory.createEmptyBorder(9, 14, 9, 14)));
        trigger.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        trigger.addActionListener(e -> showPopup());
        add(trigger, BorderLayout.CENTER);

        updateLabel();
    }

    void onApply(BiConsumer<LocalDate, LocalDate> listener) {
        this.onApply = listener;
    }

    LocalDate from() { return from; }
    LocalDate to() { return to; }

    private void updateLabel() {
        if (from.equals(ALL_TIME_START)) {
            trigger.setText("All Time");
            return;
        }
        trigger.setText(from.equals(to) ? from.format(LABEL_FMT)
            : from.format(LABEL_FMT) + "  →  " + to.format(LABEL_FMT));
    }

    private void applyRange(LocalDate f, LocalDate t) {
        this.from = f;
        this.to = t;
        updateLabel();
        onApply.accept(f, t);
    }

    // -------------------------------------------------------------- popup

    private void showPopup() {
        viewMonth = YearMonth.from(to);
        pendingStart = from;
        pendingEnd = to;

        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));
        popup.setLayout(new BorderLayout());

        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.setBackground(Theme.SURFACE);
        content.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        content.add(buildPresetColumn(popup), BorderLayout.WEST);
        content.add(buildCalendarColumn(popup), BorderLayout.CENTER);

        popup.add(content, BorderLayout.CENTER);
        popup.pack();
        popup.show(trigger, 0, trigger.getHeight() + 4);
    }

    private JComponent buildPresetColumn(JPopupMenu popup) {
        JPanel col = new JPanel();
        col.setBackground(Theme.SURFACE);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER),
            BorderFactory.createEmptyBorder(8, 4, 8, 4)));

        for (Map.Entry<String, LocalDate[]> preset : presets().entrySet()) {
            JButton b = new JButton(preset.getKey());
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setFont(Theme.FONT_BODY);
            b.setForeground(Theme.TEXT);
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 20));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(160, 34));
            b.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { b.setBackground(Theme.SURFACE_HOVER); b.setContentAreaFilled(true); }
                @Override public void mouseExited(MouseEvent e) { b.setContentAreaFilled(false); }
            });
            LocalDate[] range = preset.getValue();
            b.addActionListener(e -> {
                popup.setVisible(false);
                applyRange(range[0], range[1]);
            });
            col.add(b);
        }
        return col;
    }

    /** Business-date-aware, matching the rest of the app's 4am cutover — "Today" here
     *  means the same day the Dashboard's own default filter already uses. */
    private Map<String, LocalDate[]> presets() {
        LocalDate today = OrderDao.businessDate(ZonedDateTime.now());
        Map<String, LocalDate[]> map = new LinkedHashMap<>();
        map.put("Today", new LocalDate[]{today, today});
        map.put("Yesterday", new LocalDate[]{today.minusDays(1), today.minusDays(1)});
        map.put("Last 7 Days", new LocalDate[]{today.minusDays(6), today});
        map.put("Last 30 Days", new LocalDate[]{today.minusDays(29), today});
        map.put("This Month", new LocalDate[]{today.withDayOfMonth(1), today});
        map.put("Last Month", new LocalDate[]{
            today.minusMonths(1).withDayOfMonth(1),
            today.withDayOfMonth(1).minusDays(1)});
        map.put("This Quarter", new LocalDate[]{startOfQuarter(today), today});
        map.put("Last Quarter", new LocalDate[]{
            startOfQuarter(today).minusMonths(3),
            startOfQuarter(today).minusDays(1)});
        map.put("This Year", new LocalDate[]{today.withDayOfYear(1), today});
        map.put("Last Year", new LocalDate[]{
            today.minusYears(1).withDayOfYear(1),
            today.withDayOfYear(1).minusDays(1)});
        map.put("All Time", new LocalDate[]{ALL_TIME_START, today});
        return map;
    }

    private static LocalDate startOfQuarter(LocalDate date) {
        int qNumber = date.get(IsoFields.QUARTER_OF_YEAR);
        Month firstMonthOfQuarter = Month.of((qNumber - 1) * 3 + 1);
        return LocalDate.of(date.getYear(), firstMonthOfQuarter, 1);
    }

    private JComponent buildCalendarColumn(JPopupMenu popup) {
        JPanel col = new JPanel(new BorderLayout(0, 10));
        col.setBackground(Theme.SURFACE);
        col.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JButton prev = navButton("‹");
        JButton next = navButton("›");
        monthLabel = new JLabel("", SwingConstants.CENTER);
        monthLabel.setFont(Theme.FONT_BODY_BOLD);
        prev.addActionListener(e -> { viewMonth = viewMonth.minusMonths(1); rebuildCalendarGrid(); });
        next.addActionListener(e -> { viewMonth = viewMonth.plusMonths(1); rebuildCalendarGrid(); });
        header.add(prev, BorderLayout.WEST);
        header.add(monthLabel, BorderLayout.CENTER);
        header.add(next, BorderLayout.EAST);
        col.add(header, BorderLayout.NORTH);

        calendarGrid = new JPanel(new GridLayout(0, 7, 2, 2));
        calendarGrid.setOpaque(false);
        col.add(calendarGrid, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        JButton cancel = UiFactory.secondaryButton("Cancel");
        cancel.addActionListener(e -> popup.setVisible(false));
        applyButton = UiFactory.primaryButton("Apply");
        applyButton.addActionListener(e -> {
            if (pendingStart != null && pendingEnd != null) {
                popup.setVisible(false);
                applyRange(pendingStart, pendingEnd);
            }
        });
        footer.add(cancel);
        footer.add(applyButton);
        col.add(footer, BorderLayout.SOUTH);

        rebuildCalendarGrid();
        return col;
    }

    private JButton navButton(String glyph) {
        JButton b = new JButton(glyph);
        b.setFont(Theme.FONT_HEADING);
        b.setForeground(Theme.TEXT_MUTED);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private void rebuildCalendarGrid() {
        calendarGrid.removeAll();
        monthLabel.setText(viewMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + viewMonth.getYear());

        for (DayOfWeek d : DayOfWeek.values()) {
            JLabel l = new JLabel(d.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), SwingConstants.CENTER);
            l.setFont(Theme.FONT_SMALL_BOLD);
            l.setForeground(Theme.TEXT_MUTED);
            calendarGrid.add(l);
        }

        LocalDate first = viewMonth.atDay(1);
        int leadingBlanks = first.getDayOfWeek().getValue() % 7; // Sunday-first grid
        for (int i = 0; i < leadingBlanks; i++) calendarGrid.add(new JLabel(""));

        for (int day = 1; day <= viewMonth.lengthOfMonth(); day++) {
            LocalDate date = viewMonth.atDay(day);
            calendarGrid.add(dayCell(date));
        }

        applyButton.setEnabled(pendingStart != null && pendingEnd != null);
        calendarGrid.revalidate();
        calendarGrid.repaint();
    }

    private JComponent dayCell(LocalDate date) {
        boolean isStart = date.equals(pendingStart);
        boolean isEnd = date.equals(pendingEnd);
        boolean inRange = pendingStart != null && pendingEnd != null
            && !date.isBefore(pendingStart) && !date.isAfter(pendingEnd);
        boolean edge = isStart || isEnd;

        JLabel cell = new JLabel(String.valueOf(date.getDayOfMonth()), SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                if (edge || inRange) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(edge ? Theme.PRIMARY : Theme.PRIMARY_TINT);
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 8, 8);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        cell.setPreferredSize(new Dimension(30, 28));
        cell.setFont(edge ? Theme.FONT_BODY_BOLD : Theme.FONT_BODY);
        cell.setForeground(edge ? Theme.ON_PRIMARY : Theme.TEXT);
        cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cell.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (pendingStart == null || pendingEnd != null) {
                    // Starting a fresh selection.
                    pendingStart = date;
                    pendingEnd = null;
                } else if (date.isBefore(pendingStart)) {
                    pendingEnd = pendingStart;
                    pendingStart = date;
                } else {
                    pendingEnd = date;
                }
                rebuildCalendarGrid();
            }
        });
        return cell;
    }

    private static Icon calendarIcon() {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                g2.setColor(Theme.TEXT_MUTED);
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new java.awt.geom.RoundRectangle2D.Float(1, 2.5f, 15, 13, 3, 3));
                g2.draw(new java.awt.geom.Line2D.Float(1, 7, 16, 7));
                g2.draw(new java.awt.geom.Line2D.Float(5, 0.5f, 5, 4));
                g2.draw(new java.awt.geom.Line2D.Float(12, 0.5f, 12, 4));
                g2.dispose();
            }
            @Override public int getIconWidth() { return 17; }
            @Override public int getIconHeight() { return 17; }
        };
    }
}
