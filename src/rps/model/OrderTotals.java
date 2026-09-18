package rps.model;

import rps.util.Money;

/**
 * All five money figures on an order, grouped so they can only ever be copied as a unit.
 * Order.java used to carry subtotal/discountTotal/taxTotal/total/cashTendered as five
 * adjacent positional record components — easy to transpose with no compile error and
 * no test failure, just silently wrong numbers on a receipt. Grouping them here means a
 * copy-with-changes touches one field, not five in the right order.
 */
public record OrderTotals(
    Money subtotal,
    Money discountTotal,
    Money taxTotal,
    Money deliveryFee,   // nonzero only for a DELIVERY order under the configured subtotal threshold
    Money total,
    Money cashTendered,  // null if not recorded (e.g. this order predates the field, or wasn't cash)
    Money amountPaid     // cumulative amount collected so far, capped at total; never null
) {
    /** Change owed, or ZERO if cash wasn't tendered (should not be displayed in that case). */
    public Money changeDue() {
        return cashTendered == null ? Money.ZERO : cashTendered.subtractClamped(total);
    }

    public boolean hasCashTendered() {
        return cashTendered != null;
    }

    public Money balanceDue() {
        return total.subtractClamped(amountPaid);
    }

    public boolean isFullyPaid() {
        return amountPaid.compareTo(total) >= 0;
    }
}
