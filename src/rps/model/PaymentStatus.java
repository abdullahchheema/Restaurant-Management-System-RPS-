package rps.model;

/**
 * How much of the order's money has actually been collected. Derived from amount_paid
 * against the order total by OrderDao.applyPayment — never set by hand:
 *   UNPAID          nothing collected yet
 *   PARTIALLY_PAID  some money in, but short of the total
 *   PAID            amount_paid has reached the total; nothing left to collect
 *
 * <p>Cancellation deliberately does NOT live here. An order that is cancelled after the
 * customer paid is still, factually, an order that was paid — the cancellation belongs to
 * {@link FulfilmentStatus}, and it is that track (not this one) which decides whether the
 * money counts toward the day's takings.
 *
 * <p>UNPAID and PARTIALLY_PAID differ only in presentation and reporting — operationally
 * they are the same state ("this order still owes money"), which is what {@link #awaitsPayment()}
 * expresses. Prefer that over comparing to UNPAID directly, so a partly-paid order is
 * never accidentally treated as settled.
 */
public enum PaymentStatus {
    UNPAID("Unpaid"),
    PARTIALLY_PAID("Partially Paid"),
    PAID("Paid");

    private final String label;

    PaymentStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True while this order still owes money — the single check to use anywhere "not yet
     *  settled" matters (outstanding totals, what a rider collects, whether a payment can
     *  be recorded). */
    public boolean awaitsPayment() {
        return this == UNPAID || this == PARTIALLY_PAID;
    }

    public boolean isPaid() {
        return this == PAID;
    }

    @Override
    public String toString() {
        return label;
    }
}
