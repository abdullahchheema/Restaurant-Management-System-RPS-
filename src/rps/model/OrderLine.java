package rps.model;

import rps.util.Money;

/** A persisted order line, as read back from the database (snapshotted values). */
public record OrderLine(
    long id,
    long orderId,
    int lineNo,
    Integer menuItemId,
    Integer variantId,
    String itemName,
    String sizeLabel,   // null if the item has no sizes
    Money unitPrice,
    int quantity,
    Money lineTotal,
    String notes,
    Integer optionValueId,     // null if this line has no chosen option
    String optionGroupName,    // snapshot at order time, e.g. "Crust Type"
    String optionValueName     // snapshot at order time, e.g. "Stuffed"
) {
    /** "Chicken Tikka Pizza (Large)" or just "Coca-Cola" when size-less. */
    public String displayName() {
        return sizeLabel == null ? itemName : itemName + " (" + sizeLabel + ")";
    }

    public boolean hasOption() {
        return optionValueName != null && !optionValueName.isBlank();
    }
}
