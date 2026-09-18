package rps.util;

import rps.db.OrderDao;
import rps.model.Order;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Writes the dashboard's currently filtered orders to a CSV file, no save dialog. */
public final class CsvExport {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.ROOT);
    private static final DateTimeFormatter CELL_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT);

    private CsvExport() {}

    public static Path exportOrders(List<OrderDao.OrderRow> rows) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), "Documents", "RoyalPizzaSahowala", "exports");
        Files.createDirectories(dir);
        Path file = dir.resolve("orders-" + java.time.LocalDateTime.now().format(FILE_TS) + ".csv");

        StringBuilder sb = new StringBuilder();
        sb.append("Order Number,Date,Type,Status,Items,Subtotal,Discount,Total,Amount Paid,Balance Due,Cash Tendered,Change,Staff,Phone\n");
        for (OrderDao.OrderRow row : rows) {
            Order o = row.order();
            sb.append(csv(o.orderNumber())).append(',')
              .append(csv(o.createdAt().format(CELL_TS))).append(',')
              .append(csv(o.type().label())).append(',')
              .append(csv(o.status().label())).append(',')
              .append(row.itemCount()).append(',')
              .append(o.totals().subtotal().asBigDecimal()).append(',')
              .append(o.totals().discountTotal().asBigDecimal()).append(',')
              .append(o.totals().total().asBigDecimal()).append(',')
              .append(o.totals().amountPaid().asBigDecimal()).append(',')
              .append(o.totals().balanceDue().asBigDecimal()).append(',')
              .append(o.totals().hasCashTendered() ? o.totals().cashTendered().asBigDecimal().toString() : "").append(',')
              .append(o.totals().hasCashTendered() ? o.totals().changeDue().asBigDecimal().toString() : "").append(',')
              .append(csv(o.staffName())).append(',')
              .append(csv(o.customerPhone() == null ? "" : o.customerPhone())).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Quotes per RFC 4180 AND neutralises spreadsheet formula injection.
     *
     * <p>Excel and LibreOffice treat a cell beginning with = + - @ (or a leading tab/CR)
     * as a formula, so a menu item or staff name saved as {@code =HYPERLINK(...)} would
     * execute on open rather than display as text. These values are operator-entered
     * rather than public input, but this file is opened outside the app — often mailed to
     * an accountant — so the export is the wrong place to assume the data is safe.
     * A leading apostrophe forces text interpretation and is stripped by the spreadsheet
     * on display. Carriage return is included in the quote test because a lone \r would
     * otherwise split the record.
     */
    private static String csv(String value) {
        if (value == null) return "";
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        boolean needsQuote = safe.contains(",") || safe.contains("\"")
            || safe.contains("\n") || safe.contains("\r");
        String escaped = safe.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }
}
