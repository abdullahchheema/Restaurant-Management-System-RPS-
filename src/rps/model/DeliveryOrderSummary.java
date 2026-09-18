package rps.model;

import rps.util.Money;

/** A lightweight view of one order assigned to a delivery run — just enough to show
 *  and total in the Deliveries screen, not the full Order/lines. A run can carry any
 *  order type (Dine-in and Takeaway included, not Delivery-typed orders only — a rider
 *  might drop off a Takeaway order too), so the type is shown alongside the order. */
public record DeliveryOrderSummary(
    long orderId,
    String orderNumber,
    OrderType type,
    OrderStatus status,
    Money total,
    Money balanceDue,
    String customerName,
    String deliveryAddress,
    String itemsSummary   // e.g. "2x Chicken Tikka Pizza (Large), 1x Coca-Cola"
) {
}
