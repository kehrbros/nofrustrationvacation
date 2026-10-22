package com.vacantagent.market.logic;

import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.data.ProviderOfferRepository;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.user.logic.AppUserProvisioning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end: market persists -> engine thread -> agreement writer thread -> H2. The engine is shared between tests,
 * so each test uses its own model name: waiting orders of one test must not count as duplicates in another.
 */
@SpringBootTest
class MarketLogicTest {

    @Autowired
    private IMarketLogic market;
    @Autowired
    private ConsumerOrderRepository orders;
    @Autowired
    private ProviderOfferRepository offers;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;

    private long user;

    @BeforeEach
    void setUp() {
        user = appUserProvisioning.findOrCreate("broker-user", null, null).getId();
    }

    @Test
    void offerAndOrderArePersistedAgreeAndTheAgreementIsPersisted() {
        Submission offer = market.acceptOffer(new OfferDto(usd("10.00"), usd("12.50"),
                null, "provider", "agree-model", 1_000_000L), user);
        assertThat(offer.result().join()).isInstanceOf(MatchResult.Resting.class);

        Submission order = market.acceptOrder(new OrderDto(usd("11.00"), usd("13.00"),
                Duration.ofMinutes(5), "provider", "agree-model"), user);
        MatchResult result = order.result().join();

        assertThat(result).isInstanceOf(MatchResult.Matched.class);
        Agreement agreement = ((MatchResult.Matched) result).agreement();
        assertThat(agreement.getId()).isNotZero();

        // The future completes after the save, so the row is already there.
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM agreement WHERE id = ?", agreement.getId());
        assertThat(row.get("consumer_order_id")).isEqualTo(order.id());
        assertThat(row.get("provider_offer_id")).isEqualTo(offer.id());
        assertThat((BigDecimal) row.get("agreed_price_per_million_output_tokens")).isEqualByComparingTo("12.50");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agreement_aud WHERE id = ?",
                Integer.class, row.get("id"))).isEqualTo(1);
    }

    @Test
    void entitiesArePersistedBeforeMatching() {
        Submission order = market.acceptOrder(new OrderDto(usd("1.00"), usd("1.00"), null, "provider", "persist-model"), user);
        Submission offer = market.acceptOffer(new OfferDto(usd("50.00"), usd("50.00"),
                null, "provider", "persist-model", 500L), user);

        assertThat(orders.findById(order.id())).isPresent();
        ProviderOffer saved = offers.findById(offer.id()).orElseThrow();
        assertThat(saved.getAvailableTokens()).isEqualTo(500L);
        assertThat(saved.getTotalTokens()).isEqualTo(500L);
        assertThat(order.result().join()).isInstanceOf(MatchResult.Resting.class);
        assertThat(offer.result().join()).isInstanceOf(MatchResult.Resting.class);
    }

    @Test
    void orderWithoutPricesIsPersistedButRejected() {
        Submission order = market.acceptOrder(new OrderDto(null, null, null, null, null), user);

        assertThat(orders.findById(order.id())).isPresent();
        assertThat(order.result().join()).isInstanceOf(MatchResult.Rejected.class);
    }

    @Test
    void ordersAndOffersBelongToTheUserWhoPlacedThem() {
        long otherUser = appUserProvisioning.findOrCreate("other-broker-user", null, null).getId();
        Submission order = market.acceptOrder(new OrderDto(null, null, null, null, null), user);
        Submission offer = market.acceptOffer(new OfferDto(null, null, null, "provider", "owner-model", 1L), user);
        order.result().join();
        offer.result().join();

        assertThat(market.isOrderOwnedBy(order.id(), user)).isTrue();
        assertThat(market.isOrderOwnedBy(order.id(), otherUser)).isFalse();
        assertThat(market.isOrderOwnedBy(Long.MAX_VALUE, user)).isFalse();
        assertThat(market.isOfferOwnedBy(offer.id(), user)).isTrue();
        assertThat(market.isOfferOwnedBy(offer.id(), otherUser)).isFalse();
        assertThat(market.isOfferOwnedBy(Long.MAX_VALUE, user)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT app_user_id FROM consumer_order WHERE id = ?",
                Long.class, order.id())).isEqualTo(user);
    }

    @Test
    void secondWaitingOrderForTheSameModelIsRejected() {
        // No offers for this provider exist in the shared engine, so the first order keeps waiting.
        OrderDto dto = new OrderDto(usd("1.00"), usd("1.00"), null, "duplicate-provider", "model");
        Submission first = market.acceptOrder(dto, user);
        assertThat(first.result().join()).isInstanceOf(MatchResult.Resting.class);

        MatchResult second = market.acceptOrder(dto, user).result().join();

        assertThat(second).isInstanceOf(MatchResult.Rejected.class);
        assertThat(((MatchResult.Rejected) second).reason()).endsWith(": " + first.id());
        assertThat(market.cancelOrder(first.id()).join()).isInstanceOf(MatchResult.Cancelled.class);
        assertThat(market.acceptOrder(dto, user).result().join()).isInstanceOf(MatchResult.Resting.class);
    }

    private static MoneyDto usd(String amount) {
        return new MoneyDto(new BigDecimal(amount), "USD");
    }
}
