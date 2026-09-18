package rps.model;

import rps.util.Money;

import java.util.List;

public record MenuItem(
    int id,
    int categoryId,
    String name,
    String description,
    boolean sized,
    boolean available,
    int displayOrder,
    List<MenuItemVariant> variants,
    OptionGroup optionGroup   // null when this item has no attached option group
) {
    /** Lowest price across variants — used for "from Rs X" tile labels on sized items. */
    public Money lowestPrice() {
        return variants.stream()
            .map(MenuItemVariant::price)
            .min(Money::compareTo)
            .orElse(Money.ZERO);
    }

    /** For single-price items: the one variant's price. */
    public Money singlePrice() {
        return variants.isEmpty() ? Money.ZERO : variants.get(0).price();
    }

    public boolean hasOptions() {
        return optionGroup != null && !optionGroup.values().isEmpty();
    }
}
