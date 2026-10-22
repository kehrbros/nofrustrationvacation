package com.vacantagent.market.logic;

import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.vacantagent.market.MarketFixtures.bookOffer;
import static com.vacantagent.market.MarketFixtures.bookOrder;
import static com.vacantagent.market.MarketFixtures.emptyOffer;
import static com.vacantagent.market.MarketFixtures.emptyOrder;
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

    @Test
    void offerIsInvalidOnceOfferedUntilIsReached() {
        MarketRules fixed = new MarketRules(100_00, Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));

        assertThat(fixed.isValid(bookOffer(1, 1_00, 1_00, 1_001))).isTrue();
        assertThat(fixed.isValid(bookOffer(1, 1_00, 1_00, 1_000))).isFalse();
        assertThat(fixed.isValid(bookOffer(1, 1_00, 1_00))).isTrue();     // no offeredUntil
    }

    @Test
    void orderExpiresAfterItsWaitTime() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        BookOrder order = new BookOrder(new ConsumerOrder(null, null, Duration.ofSeconds(30), createdAt), 1_00, 1_00);
        long expiresAt = createdAt.plusSeconds(30).toInstant().toEpochMilli();

        assertThat(rules.isExpired(order, expiresAt - 1)).isFalse();
        assertThat(rules.isExpired(order, expiresAt)).isTrue();
        assertThat(rules.isValid(order)).isTrue();                        // may still match on arrival
        assertThat(rules.isExpired(new BookOrder(emptyOrder(), 1_00, 1_00), Long.MAX_VALUE - 1)).isFalse();
    }

    private static ProviderOffer offerWithLastError(OffsetDateTime lastErrorAt) {
        ProviderOffer offer = withId(emptyOffer(), 1);
        ReflectionTestUtils.setField(offer, "lastErrorAt", lastErrorAt);
        return offer;
    }
}