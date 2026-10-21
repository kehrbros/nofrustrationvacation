package com.vacantagent.market.matching;

import com.vacantagent.common.money.Money;
import com.vacantagent.market.logic.MarketRules;
import com.vacantagent.market.matching.entities.Prices;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;

import static com.vacantagent.market.MarketFixtures.bookOrder;
import static org.assertj.core.api.Assertions.assertThat;

class PricesTest {

    @Test
    void convertsToMicros() {
        assertThat(Prices.toMicros(Money.of("2.5"), RoundingMode.FLOOR)).isEqualTo(2_500_000);
    }

    @Test
    void roundsExtraDecimalsInTheGivenDirection() {
        assertThat(Prices.toMicros(Money.of("0.0000015"), RoundingMode.FLOOR)).isEqualTo(1);
        assertThat(Prices.toMicros(Money.of("0.0000015"), RoundingMode.CEILING)).isEqualTo(2);
    }

    @Test
    void nullAndHugeValuesBecomeInvalidPrices() {
        MarketRules rules = new MarketRules(100_00);
        long fromNull = Prices.toMicros(null, RoundingMode.FLOOR);
        long fromHuge = Prices.toMicros(Money.of("1e30"), RoundingMode.FLOOR);

        assertThat(fromHuge).isEqualTo(Long.MAX_VALUE);
        assertThat(rules.isValid(bookOrder(1, fromNull, 1))).isFalse();
        assertThat(rules.isValid(bookOrder(1, fromHuge, 1))).isFalse();
    }
}