package rps.model;

import rps.util.Money;

/**
 * One line in an order still being built at the POS (not yet saved).
 * Carries a variantId, but price is only ever used here for the live ticket total —
 * at confirm time the server re-reads the authoritative price and never trusts this
 * client-side copy (see plan Phase 5).
 */
public record DraftLine(
    int variantId,
    String displayName,   // for the on-screen ticket only; not persisted as-is
    Money unitPrice,       // display-only; server re-reads the real price at confirm
    int quantity,
    String notes,
    Integer optionValueId,     // null when the item has no attached option group
    String optionGroupName,    // display-only; for the on-screen ticket
    String optionValueName     // display-only; for the on-screen ticket
) {
    public DraftLine withQuantity(int newQuantity) {
        return new DraftLine(variantId, displayName, unitPrice, newQuantity, notes,
            optionValueId, optionGroupName, optionValueName);
    }

    public Money lineTotal() {
        return unitPrice.multiply(quantity);
    }
}
