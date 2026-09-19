package rps.model;

/**
 * Where the order is in the kitchen and at the counter — deliberately independent of
 * whether it has been paid for:
 *   PENDING    being prepared, or waiting to go out
 *   COMPLETED  handed to the customer / delivered
 *   CANCELLED  will not be made or has been voided
 *
 * <p>This is the track the counter drives by hand. A delivery order is routinely cooked
 * and handed over before any cash comes back, and a dine-in customer can pay up front and
 * still be waiting for food, so neither track can be derived from the other — see
 * {@link PaymentStatus}, which moves on its own from amount_paid.
 */
public enum FulfilmentStatus {
    PENDING("Pending"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    FulfilmentStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isCancelled() {
        return this == CANCELLED;
    }

    /** Still a real order: not cancelled, so it counts toward takings and money owed. */
    public boolean isLive() {
        return this != CANCELLED;
    }

    /**
     * Whether moving from this state to {@code next} is legal.
     *
     * <p>CANCELLED is terminal — a cancelled order never revives, because resurrecting one
     * would silently put its total back into revenue. COMPLETED can still be cancelled
     * (food handed over then voided does happen, and the money side is settled separately),
     * but it cannot go back to PENDING: "un-completing" an order is not a real operation,
     * it is a mistake best fixed by cancelling and re-entering.
     */
    public boolean canTransitionTo(FulfilmentStatus next) {
        if (next == null) return false;
        if (this == next) return true;
        if (this == CANCELLED) return false;
        if (next == CANCELLED) return true;
        return this == PENDING && next == COMPLETED;
    }
}
