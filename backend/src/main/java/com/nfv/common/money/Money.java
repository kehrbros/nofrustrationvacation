package com.nfv.common.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Monetary amount in the base currency {@link #BASE_CURRENCY}. The backend calculates and persists only in the
 * base currency, other currencies exist only at the API boundary as {@link CurrencyAmount}, see Docs/money.md.
 * <p>
 * The amount is always normalized to {@link #SCALE} decimal places, so {@code equals} is safe to use.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {
    public static final Currency BASE_CURRENCY = Currency.getInstance("USD");
    public static final int SCALE = 8;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    public Money {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor));
    }

    public Money times(long factor) {
        return times(BigDecimal.valueOf(factor));
    }

    public Money dividedBy(BigDecimal divisor) {
        return new Money(amount.divide(divisor, MathContext.DECIMAL128));
    }

    /**
     * Cost of the given number of tokens, if this is a price per million tokens.
     */
    public Money costFor(long tokens) {
        return times(tokens).dividedBy(ONE_MILLION);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + BASE_CURRENCY.getCurrencyCode();
    }
}
