package rps.model;

import rps.util.AppSettings;
import rps.util.Money;
import rps.util.Validators;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable cart built on the POS screen before it is confirmed and saved. reset() is the
 * ONLY sanctioned way to clear it between orders — a discount or cash-tendered value
 * left over from the previous customer must never leak into the next one, so every field
 * this class owns is cleared in exactly one place, next to its declaration.
 */
public final class OrderDraft {

    private final List<DraftLine> lines = new ArrayList<>();
    private OrderType type = OrderType.DINE_IN;
    private String tableNumber;
    private String customerName;
    private String customerPhone;
    private String deliveryAddress;
    private String notes;
    private DiscountMode discountMode = DiscountMode.NONE;
    private BigDecimal discountValue;   // percent (0-100) if PERCENT, rupee amount if AMOUNT
    private Money cashTendered;

    /** Clears every field back to its initial state. Call this instead of hand-clearing
     *  fields one by one — that pattern is exactly how a discount would leak to the next
     *  customer if a new field were ever added and forgotten at the call site. */
    public void reset() {
        lines.clear();
        type = OrderType.DINE_IN;
        tableNumber = null;
        customerName = null;
        customerPhone = null;
        deliveryAddress = null;
        notes = null;
        discountMode = DiscountMode.NONE;
        discountValue = null;
        cashTendered = null;
    }

    /**
     * An immutable point-in-time copy, taken on the EDT at the moment the cashier
     * confirms and handed to the background save thread in place of this live object.
     *
     * <p>Without it the worker thread reads a draft the EDT can still mutate: measured
     * at 10 out of 40 concurrent runs, orders were saved containing lines added AFTER
     * the click — the customer charged for items that were never on the confirmed
     * ticket. DraftLine is an immutable record, so copying the list is a deep enough
     * copy; every other field is an immutable value.
     */
    public OrderDraft snapshot() {
        OrderDraft copy = new OrderDraft();
        copy.lines.addAll(this.lines);
        copy.type = this.type;
        copy.tableNumber = this.tableNumber;
        copy.customerName = this.customerName;
        copy.customerPhone = this.customerPhone;
        copy.deliveryAddress = this.deliveryAddress;
        copy.notes = this.notes;
        copy.discountMode = this.discountMode;
        copy.discountValue = this.discountValue;
        copy.cashTendered = this.cashTendered;
        return copy;
    }

    public List<DraftLine> lines() {
        return lines;
    }

    public void addLine(DraftLine line) {
        lines.add(line);
    }

    public void removeLine(int index) {
        lines.remove(index);
    }

    public void updateQuantity(int index, int quantity) {
        lines.set(index, lines.get(index).withQuantity(quantity));
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public OrderType type() {
        return type;
    }

    public void setType(OrderType type) {
        this.type = type;
    }

    public String tableNumber() {
        return tableNumber;
    }

    public void setTableNumber(String tableNumber) {
        this.tableNumber = tableNumber;
    }

    public String customerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String customerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String deliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public String notes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public DiscountMode discountMode() {
        return discountMode;
    }

    public BigDecimal discountValue() {
        return discountValue;
    }

    public void setDiscount(DiscountMode mode, BigDecimal value) {
        this.discountMode = mode == null ? DiscountMode.NONE : mode;
        this.discountValue = this.discountMode == DiscountMode.NONE ? null : value;
    }

    public Money cashTendered() {
        return cashTendered;
    }

    public void setCashTendered(Money cashTendered) {
        this.cashTendered = cashTendered;
    }

    /** Phone is required and format-checked for Takeaway/Delivery; optional for Dine-in
     *  (no pickup contact or address to confirm it against), but if one is entered
     *  anyway it still has to be well-formed rather than silently accepted as garbage.
     *  Address is required only for Delivery. Table number is required only for Dine-in. */
    public boolean isCustomerInfoValid() {
        boolean phoneBlank = Validators.isBlank(customerPhone);
        if (type == OrderType.DINE_IN) {
            if (!phoneBlank && !Validators.isValidPakistaniPhone(customerPhone)) return false;
            if (Validators.isBlank(tableNumber)) return false;
        } else if (!Validators.isValidPakistaniPhone(customerPhone)) {
            return false;
        }
        if (type == OrderType.DELIVERY) {
            return Validators.isValidAddress(deliveryAddress);
        }
        return true;
    }

    /** Display-only: the server always re-derives and clamps the real figure at Confirm. */
    public Money estimatedDiscount() {
        Money sub = estimatedSubtotal();
        return switch (discountMode) {
            case NONE -> Money.ZERO;
            case PERCENT -> discountValue == null ? Money.ZERO
                : clampToSubtotal(sub.percentOf(discountValue), sub);
            case AMOUNT -> discountValue == null ? Money.ZERO
                : clampToSubtotal(Money.of(discountValue), sub);
        };
    }

    private static Money clampToSubtotal(Money value, Money subtotal) {
        if (value.isNegative()) return Money.ZERO;
        return value.compareTo(subtotal) > 0 ? subtotal : value;
    }

    public Money estimatedSubtotal() {
        Money total = Money.ZERO;
        for (DraftLine l : lines) {
            total = total.add(l.lineTotal());
        }
        return total;
    }

    /** Display-only estimate; the server (OrderDao.rollupTotals) is the source of truth
     *  and re-derives this from the same AppSettings values at save time. */
    public Money estimatedDeliveryFee() {
        if (type != OrderType.DELIVERY) return Money.ZERO;
        AppSettings settings = AppSettings.get();
        return estimatedSubtotal().asBigDecimal().doubleValue() < settings.deliveryFeeThreshold()
            ? Money.fromDouble(settings.deliveryFeeAmount())
            : Money.ZERO;
    }

    /** Client-side estimate for the on-screen ticket only; the server computes the real total. */
    public Money estimatedTotal() {
        return estimatedSubtotal().subtractClamped(estimatedDiscount()).add(estimatedDeliveryFee());
    }

    /** Change due on the entered cash, or ZERO if nothing has been entered yet. */
    public Money estimatedChangeDue() {
        if (cashTendered == null) return Money.ZERO;
        return cashTendered.subtractClamped(estimatedTotal());
    }

    public boolean isCashSufficient() {
        return cashTendered == null || cashTendered.compareTo(estimatedTotal()) >= 0;
    }

    /** A short (or absent) cash amount no longer blocks confirming — the order is simply
     *  saved as Pending. Only the presence of items and valid customer info gate Confirm. */
    public boolean canConfirm() {
        return !isEmpty() && isCustomerInfoValid();
    }

    public List<Integer> variantIds() {
        return lines.stream().map(DraftLine::variantId).toList();
    }

    public List<Integer> optionValueIds() {
        return lines.stream().map(DraftLine::optionValueId).filter(java.util.Objects::nonNull).toList();
    }
}
