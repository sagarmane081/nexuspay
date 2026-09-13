package com.nexuspay.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in a specific currency.
 * <p>
 * Two rules make this type worth having:
 * <ol>
 *   <li><b>Currency travels with the amount.</b> A bare {@code BigDecimal} lets
 *       ¥5,000 and $5,000 be added together and nobody notices until
 *       settlement. Every operation here refuses to mix currencies.</li>
 *   <li><b>Scale matches the currency's minor units.</b> JPY has zero, so
 *       ¥5,000.50 is not a representable amount and is rejected on
 *       construction — the same rule the database enforces via
 *       {@code valid_minor_units()}. Catching it here gives a good error
 *       message; catching it there guarantees it.</li>
 * </ol>
 * Immutable, and never backed by {@code double} or {@code float}.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        int minorUnits = currency.getDefaultFractionDigits();
        if (minorUnits < 0) {
            throw new IllegalArgumentException(
                    "%s is not a funding currency and has no minor units".formatted(currency.getCurrencyCode()));
        }

        // stripTrailingZeros first, so 5000.00 JPY is accepted (it is really 5000)
        // while 5000.50 JPY is not.
        if (amount.stripTrailingZeros().scale() > minorUnits) {
            throw new IllegalArgumentException(
                    "%s has %d minor unit(s) but amount %s requires %d".formatted(
                            currency.getCurrencyCode(), minorUnits,
                            amount.toPlainString(), amount.stripTrailingZeros().scale()));
        }

        amount = amount.setScale(minorUnits, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(long amount, String currencyCode) {
        return new Money(BigDecimal.valueOf(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * Applies a rate — a fee percentage, typically — rounding to the currency's
     * minor units with HALF_UP, per business-requirements.md section 7.
     * <p>
     * Note that rounding here is why fee components must never each be computed
     * independently and then summed: three HALF_UP roundings against JPY's zero
     * minor units will not add up to the total they were derived from. One
     * component must always be the residual.
     */
    public Money multipliedBy(BigDecimal rate) {
        Objects.requireNonNull(rate, "rate must not be null");
        return new Money(
                amount.multiply(rate).setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP),
                currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
