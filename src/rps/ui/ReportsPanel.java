package rps.ui;

import rps.app.Session;
import rps.db.Db;
import rps.db.ReportsDao;
import rps.ui.icon.LineIcon;
import rps.ui.theme.EmptyState;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

public final class ReportsPanel extends JPanel {

    private final ReportsDao reportsDao;

    private final DateRangePicker dateRangePicker;

    private final DefaultTableModel bestSellersModel = new DefaultTableModel(new Object[]{"Item", "Qty Sold", "Revenue"}, 0);
    private final DefaultTableModel hourlyModel = new DefaultTableModel(new Object[]{"Hour", "Orders", "Revenue"}, 0);
    private final DefaultTableModel staffModel = new DefaultTableModel(new Object[]{"Staff", "Orders", "Revenue"}, 0);
    private final DefaultTableModel typeModel = new DefaultTableModel(new Object[]{"Order Type", "Orders", "Revenue"}, 0);

    public ReportsPanel(Db db, Session session) {
        this.reportsDao = new ReportsDao(db);

        setLayout(new BorderLayout(0, 12));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG, Theme.SPACE_LG));

        LocalDate today = rps.db.OrderDao.businessDate(ZonedDateTime.now());
        dateRangePicker = new DateRangePicker(today.minusDays(6), today);
        dateRangePicker.onApply((f, t) -> load());

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel title = UiFactory.title("Reports");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        top.add(title);
        top.add(Box.createVerticalStrut(14));

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        filterRow.setOpaque(false);
        filterRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        filterRow.add(dateRangePicker);
        top.add(filterRow);
        add(top, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(2, 2, 14, 14));
        grid.setOpaque(false);
        grid.add(section("Best Sellers", bestSellersModel));
        grid.add(section("Sales by Hour", hourlyModel));
        grid.add(section("Staff Performance", staffModel));
        grid.add(section("Order Type Mix", typeModel));
        add(grid, BorderLayout.CENTER);

        // Busy needs a realized component hierarchy (a JRootPane) to attach its overlay
        // to — this panel doesn't have one yet while still inside its own constructor,
        // so the first load is deferred until after it's added to the window.
        SwingUtilities.invokeLater(this::load);
    }

    private JComponent section(String title, DefaultTableModel model) {
        rps.ui.theme.Card panel = new rps.ui.theme.Card(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        panel.add(UiFactory.heading(title), BorderLayout.NORTH);
        JTable table = new JTable(model) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        table.setFont(Theme.FONT_BODY);
        table.setDefaultRenderer(Object.class, UiFactory.centeredCellRenderer());
        // The count column (Qty Sold / Orders) is tinted to draw the eye, same way the
        // Dashboard's stat numbers stand out from surrounding neutral text — the
        // Revenue column stays plain since money is already the reader's default focus.
        table.getColumnModel().getColumn(1).setCellRenderer(countCellRenderer());
        TouchScroll.install(table);

        CardLayout cards = new CardLayout();
        JPanel holder = new JPanel(cards);
        EmptyState empty = new EmptyState(LineIcon.STATUS_COMPLETED, "No data for this range",
            "Widen the date range and apply again.");
        holder.add(new JScrollPane(table), "table");
        holder.add(empty, "empty");
        model.addTableModelListener(e -> cards.show(holder, model.getRowCount() == 0 ? "empty" : "table"));
        cards.show(holder, "empty");
        panel.add(holder, BorderLayout.CENTER);
        return panel;
    }

    private static DefaultTableCellRenderer countCellRenderer() {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                             boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                c.setForeground(isSelected ? Theme.TEXT : Theme.PRIMARY);
                setFont(Theme.FONT_BODY_BOLD);
                return c;
            }
        };
        r.setHorizontalAlignment(SwingConstants.CENTER);
        return r;
    }

    private void load() {
        final LocalDate f = dateRangePicker.from(), t = dateRangePicker.to();

        Busy.call(this, () -> new Object[]{
                reportsDao.bestSellers(f, t, 10),
                reportsDao.salesByHour(f, t),
                reportsDao.staffTotals(f, t),
                reportsDao.orderTypeMix(f, t)
            })
            .message("Loading reports…")
            .onSuccess(this::populate)
            .onError(e -> JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE))
            .start();
    }

    @SuppressWarnings("unchecked")
    private void populate(Object[] results) {
        bestSellersModel.setRowCount(0);
        for (ReportsDao.BestSeller r : (List<ReportsDao.BestSeller>) results[0]) {
            bestSellersModel.addRow(new Object[]{r.itemName(), r.quantity(), r.revenue().format()});
        }
        hourlyModel.setRowCount(0);
        for (ReportsDao.HourBucket r : (List<ReportsDao.HourBucket>) results[1]) {
            hourlyModel.addRow(new Object[]{String.format("%02d:00", r.hour()), r.orderCount(), r.revenue().format()});
        }
        staffModel.setRowCount(0);
        for (ReportsDao.StaffTotal r : (List<ReportsDao.StaffTotal>) results[2]) {
            staffModel.addRow(new Object[]{r.staffName(), r.orderCount(), r.revenue().format()});
        }
        typeModel.setRowCount(0);
        for (ReportsDao.TypeMix r : (List<ReportsDao.TypeMix>) results[3]) {
            typeModel.addRow(new Object[]{r.orderType(), r.orderCount(), r.revenue().format()});
        }
    }
}
