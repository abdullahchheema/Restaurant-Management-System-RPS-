package rps.model;

/**
 * Payment-driven lifecycle, derived from amount_paid against the order total rather
 * than set by hand:
 *   PENDING          nothing collected yet
 *   PARTIALLY_PAID   some money in, but short of the total — still owes a balance
 *   PAYMENT_RECEIVED amount_paid has reached the total; nothing left to collect
 *   CANCELLED        reachable from any of the above
 *
 * PENDING and PARTIALLY_PAID differ only in presentation and reporting — operationally
 * they are the same state ("this order still owes money"), which is what
 * {@link #awaitsPayment()} expresses. Prefer that over comparing to PENDING directly,
 * so a partially-paid order is never accidentally treated as settled.
 */
public enum OrderStatus {
    PENDING("Pending"),
    PARTIALLY_PAID("Partially Paid"),
    PAYMENT_RECEIVED("Payment Received"),
    CANCELLED("Cancelled");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True while this order still owes money — the single check that must be used
     *  anywhere "not yet settled" matters (outstanding totals, what a rider collects,
     *  whether a payment can be recorded). */
    public boolean awaitsPayment() {
        return this == PENDING || this == PARTIALLY_PAID;
    }

    public boolean canCancel() {
        return this != CANCELLED;
    }

    /**
     * Whether moving from this status to {@code next} is a legal transition.
     *
     * <p>CANCELLED is terminal — nothing leaves it. Everything else may be cancelled,
     * and payment states may move between themselves (an edit that changes the total
     * can legitimately move an order in either direction along the payment ladder).
     * Enforced in OrderDao.updateStatus so the rule cannot be bypassed by a caller
     * passing a wrong "expected current" value.
     */
    public boolean canTransitionTo(OrderStatus next) {
        if (next == null) return false;
        if (this == next) return true;
        if (this == CANCELLED) return false;   // terminal: a cancelled order never revives
        if (next == CANCELLED) return true;    // anything live can be cancelled
        return next.awaitsPayment() || next == PAYMENT_RECEIVED;
    }

    @Override
    public String toString() {
        return label;
    }
}
