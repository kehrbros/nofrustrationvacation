package com.vacantagent.market.entities;

import com.vacantagent.common.money.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderOfferTest {

    @Test
    void offerWithoutLimitOrWithTokensLeftHasTokensLeft() {
        assertThat(offer(null).hasTokensLeft()).isTrue();
        assertThat(offer(1L).hasTokensLeft()).isTrue();
        assertThat(offer(0L).hasTokensLeft()).isFalse();
    }

    private static ProviderOffer offer(Long availableTokens) {
        return new ProviderOffer(Money.of("1.00"), Money.of("1.00"), null, 1, "provider", "model", null, null,
                availableTokens, availableTokens, null);
    }
}