package rps.model;

import rps.util.Money;

import java.time.OffsetDateTime;
import java.util.List;

public record DeliveryRun(
    long id,
    int riderId,
    String riderName,
    DeliveryRunStatus status,
    Money collectedAmount,   // null until the run is settled
    Integer staffId,
    String staffName,        // who started the run
    OffsetDateTime createdAt,
    OffsetDateTime closedAt, // when it was completed or cancelled
    List<DeliveryOrderSummary> orders
) {
    /** What the rider is expected to hand back: the outstanding balance on every
     *  still-Pending order in the run. An order that was already Payment Received
     *  before being added (e.g. a prepaid delivery) contributes nothing here — there
     *  is nothing left to collect for it, only to physically deliver. */
    /** Total value of what the rider actually carried — every order's full total,
     *  whether it was already paid or not. Cancelled orders are excluded: they were
     *  called off, so they are not part of what went out the door. This is deliberately
     *  NOT the same as expectedAmount(), which is only the cash still to be collected;
     *  a run of entirely prepaid orders carries real value but collects nothing. */
    public Money totalValue() {
        Money sum = Money.ZERO;
        for (DeliveryOrderSummary o : orders) {
            if (o.status() != OrderStatus.CANCELLED) {
                sum = sum.add(o.total());
            }
        }
        return sum;
    }

    public Money expectedAmount() {
        Money sum = Money.ZERO;
        for (DeliveryOrderSummary o : orders) {
            // Part-paid orders count too, for the balance still owed on them — not the
            // whole total, and not zero.
            if (o.status().awaitsPayment()) {
                sum = sum.add(o.balanceDue());
            }
        }
        return sum;
    }
}
