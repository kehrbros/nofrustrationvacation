package com.nfv.common;

import com.nfv.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void amountIsNormalizedToScale8() {
        assertEquals(new BigDecimal("1.50000000"), Money.of("1.5").amount());
        assertEquals(Money.of("1.5"), Money.of(new BigDecimal("1.500")));
        assertEquals(Money.of("0.00000002"), Money.of("0.000000015"), "HALF_EVEN rounds to the even neighbour");
        assertEquals(Money.of("0.00000002"), Money.of("0.000000025"));
    }

    @Test
    void arithmetic() {
        assertEquals(Money.of("3.75"), Money.of("1.25").plus(Money.of("2.5")));
        assertEquals(Money.of("-1.25"), Money.of("1.25").minus(Money.of("2.5")));
        assertEquals(Money.of("3.75"), Money.of("1.25").times(3));
        assertEquals(Money.of("0.33333333"), Money.of("1").dividedBy(BigDecimal.valueOf(3)));
        assertTrue(Money.of("-0.00000001").isNegative());
        assertTrue(Money.of("2").isGreaterThan(Money.of("1.99999999")));
    }

    @Test
    void costForTokensOfPricePerMillionTokens() {
        assertEquals(Money.of("0.0001"), Money.of("0.10").costFor(1_000));
        assertEquals(Money.of("0.00000008"), Money.of("0.075").costFor(1));
        assertEquals(Money.of("15"), Money.of("15").costFor(1_000_000));
    }
}
