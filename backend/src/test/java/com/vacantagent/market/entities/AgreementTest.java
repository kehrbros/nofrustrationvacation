package com.vacantagent.market.entities;

import com.vacantagent.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AgreementTest {

    @Test
    void costPricesInputAndOutputTokensSeparatelyPerMillion() {
        Agreement agreement = new Agreement(null, null, OffsetDateTime.now(), null, Money.of("2.00"), Money.of("10.00"));

        // 1000 * 2 / 1M + 500 * 10 / 1M
        assertThat(agreement.costFor(1000, 500).amount()).isEqualByComparingTo("0.007");
        assertThat(agreement.costFor(0, 0)).isEqualTo(Money.ZERO);
    }
}