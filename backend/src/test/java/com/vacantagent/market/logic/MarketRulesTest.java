package com.vacantagent.market.logic;

import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.matching.entities.BookOffer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

import static com.vacantagent.market.MarketFixtures.bookOffer;
import static com.vacantagent.market.MarketFixtures.bookOrder;
import static com.vacantagent.market.MarketFixtures.emptyOffer;
import static com.vacantagent.market.MarketFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;

class MarketRulesTest {

    private final MarketRules rules = new MarketRules(100_00);

    @Test
    void offerPricesMustBePositiveAndCapped() {
        assertThat(rules.isValid(bookOffer(1, 10_00, 12_00))).isTrue();
        assertThat(rules.isValid(bookOffer(1, 0, 12_00))).isFalse();
        assertThat(rules.isValid(bookOffer(1, 10_00, 100_01))).isFalse();
    }

    @Test
    void orderPricesMustBePositiveAndCapped() {
        assertThat(rules.isValid(bookOrder(1, 100_00, 1))).isTrue();
        assertThat(rules.isValid(bookOrder(1, -5, 10_00))).isFalse();
    }

    @Test
    void anyErrorMakesTheOfferUnavailableForGood() {
        assertThat(rules.isProviderOfferAvailable(offerWithLastError(null))).isTrue();
        assertThat(rules.isProviderOfferAvailable(offerWithLastError(OffsetDateTime.now()))).isFalse();
        assertThat(rules.isProviderOfferAvailable(offerWithLastError(OffsetDateTime.now().minusYears(1)))).isFalse();
    }

    @Test
    void unavailableOfferIsInvalid() {
        assertThat(rules.isValid(new BookOffer(offerWithLastError(OffsetDateTime.now().minusDays(1)), 1_00, 1_00))).isFalse();
    }

    private static ProviderOffer offerWithLastError(OffsetDateTime lastErrorAt) {
        ProviderOffer offer = withId(emptyOffer(), 1);
        ReflectionTestUtils.setField(offer, "lastErrorAt", lastErrorAt);
        return offer;
    }
}