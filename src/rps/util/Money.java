package rps.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money value wrapper over BigDecimal, scale 2, HALF_UP.
 * Rounding is applied only here, at construction — never mid-computation.
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(2));

    private final BigDecimal value;

    private Money(BigDecimal value) {
        this.value = value;
    }

    public static Money of(BigDecimal value) {
        if (value == null) return ZERO;
        return new Money(value.setScale(2, RoundingMode.HALF_UP));
    }

    /** Use only for literal/trusted constants, e.g. Money.of("12.50"). Never parse raw doubles. */
    public static Money of(String value) {
        return of(new BigDecimal(value.trim()));
    }

    /** Safe conversion from a legacy double source (e.g. old data import) — never new BigDecimal(double). */
    public static Money fromDouble(double value) {
        return of(BigDecimal.valueOf(value));
    }

    public BigDecimal asBigDecimal() {
        return value;
    }

    public Money add(Money other) {
        return of(this.value.add(other.value));
    }

    public Money subtract(Money other) {
        return of(this.value.subtract(other.value));
    }

    public Money multiply(int quantity) {
        return of(this.value.multiply(BigDecimal.valueOf(quantity)));
    }

    /** Display-only estimate (e.g. the live POS total). The stored figure always comes
     *  back from a single SQL round() server-side — see OrderDao.rollupTotals — so this
     *  value must never be written to the database. */
    public Money percentOf(BigDecimal percent) {
        return of(this.value.multiply(percent).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
    }

    /** Never goes below zero — a discount can visually clamp without a negative flashing by. */
    public Money subtractClamped(Money other) {
        Money result = subtract(other);
        return result.isNegative() ? ZERO : result;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    @Override
    public int compareTo(Money other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        return this.value.compareTo(other.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    /**
     * "Rs 1,234.00" — always, on every machine.
     *
     * <p>Locale.ROOT is pinned rather than following the host's Windows locale: the
     * default would render this as "Rs 1.234,50" under a European locale (decimal point
     * and thousands separator swapped on a printed receipt), and under a locale whose
     * number system is Eastern Arabic or Bengali it emits non-Western digits, making
     * every price on the receipt unreadable to the shop.
     */
    public String format() {
        return "Rs " + String.format(java.util.Locale.ROOT, "%,.2f", value);
    }

    @Override
    public String toString() {
        return format();
    }
}
