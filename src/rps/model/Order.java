package rps.model;

import java.time.OffsetDateTime;
import java.util.List;

/** A persisted order, as read back from the database. */
public record Order(
    long id,
    String orderNumber,
    java.time.LocalDate businessDate,
    OrderType type,
    PaymentStatus paymentStatus,
    FulfilmentStatus fulfilmentStatus,
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
    /** When this order was moved to "pay later"; null for an ordinary order. The third
     *  track, independent of both payment and fulfilment — see Migrations, migration 20. */
    OffsetDateTime loanAt,
    /** When the debt was collected in full and the order returned to the Dashboard; null
     *  while it is still owed. Never set unless loanAt is. */
    OffsetDateTime loanSettledAt,
    List<OrderLine> lines
) {
    public boolean isDelivery() {
        return type == OrderType.DELIVERY;
    }

    public boolean isCancelled() {
        return fulfilmentStatus.isCancelled();
    }

    /** Currently a live debt on the Pay Later ledger: credit was given and has not yet been
     *  collected in full. This — not merely {@link #wasSoldOnCredit()} — is what decides
     *  whether an order appears in Pay Later or back on the Dashboard. */
    public boolean isOnLoanLedger() {
        return loanAt != null && loanSettledAt == null;
    }

    /** Credit was given on this order at some point, whether or not it has since been
     *  repaid. Kept distinct from {@link #isOnLoanLedger()} so a repaid credit sale stays
     *  distinguishable from a cash one. */
    public boolean wasSoldOnCredit() {
        return loanAt != null;
    }

    /** Money still owed on an order that is actually going to be collected — a cancelled
     *  order owes nothing, whatever its payment row says. True for a loan too: the debt is
     *  real, it is just being chased from the Pay Later screen instead of the Dashboard. */
    public boolean owesMoney() {
        return fulfilmentStatus.isLive() && paymentStatus.awaitsPayment();
    }

    /** What the customer still has to hand over — the figure the Pay Later ledger is
     *  actually about. Zero once a loan has been settled in full. */
    public rps.util.Money balanceDue() {
        return totals.balanceDue();
    }

    public Order withLines(List<OrderLine> newLines) {
        return new Order(id, orderNumber, businessDate, type, paymentStatus, fulfilmentStatus,
            staffId, staffName, createdAt, updatedAt, completedAt, totals, discountMode, discountRate,
            tableNumber, customerName, customerPhone, deliveryAddress, notes,
            loanAt, loanSettledAt, newLines);
    }
}
