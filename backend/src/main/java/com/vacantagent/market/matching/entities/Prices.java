package com.vacantagent.market.matching.entities;

import com.vacantagent.common.money.Money;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts the entities' {@link Money} prices per million tokens to the {@code long} micro units
 * the matching core works with. Matching on {@code long} avoids rounding errors and keeps the index fast.
 */
public final class Prices {

    /**
     * Decimal places kept. Coarser than {@link Money#SCALE}, so prices differing only beyond the 6th decimal
     * place are matched conservatively (offers rounded up, orders rounded down).
     */
    public static final int SCALE = 6;

    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    private Prices() {
    }

    /**
     * Price in micro units. {@code null} becomes 0 and values beyond {@code long} are clamped, so both are
     * rejected by {@link com.vacantagent.market.logic.MarketRules} instead of failing here.
     */
    public static long toMicros(@Nullable Money price, RoundingMode rounding) {
        if (price == null) return 0;
        return price.amount().movePointRight(SCALE).setScale(0, rounding).max(LONG_MIN).min(LONG_MAX).longValue();
    }
}