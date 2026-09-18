package rps.model;

import java.time.OffsetDateTime;
import java.util.List;

/** A persisted order, as read back from the database. */
public record Order(
    long id,
    String orderNumber,
    java.time.LocalDate businessDate,
    OrderType type,
    OrderStatus status,
    Integer staffId,
    String staffName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime completedAt,
    OrderTotals totals,
    DiscountMode discountMode,
    java.math.BigDecimal discountRate,   // non-null only when discountMode == PERCENT
    String tableNumber,
    String customerName,
    String customerPhone,
    String deliveryAddress,
    String notes,
    List<OrderLine> lines
) {
    public boolean isDelivery() {
        return type == OrderType.DELIVERY;
    }

    public Order withLines(List<OrderLine> newLines) {
        return new Order(id, orderNumber, businessDate, type, status, staffId, staffName,
            createdAt, updatedAt, completedAt, totals, discountMode, discountRate,
            tableNumber, customerName, customerPhone, deliveryAddress, notes, newLines);
    }
}
