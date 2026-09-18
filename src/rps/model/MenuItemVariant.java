package rps.model;

import rps.util.Money;

public record MenuItemVariant(
    int id,
    int menuItemId,
    String sizeLabel,   // null when the parent item is not sized
    Money price,
    boolean available,
    int displayOrder
) {
    public boolean hasSize() {
        return sizeLabel != null;
    }
}
