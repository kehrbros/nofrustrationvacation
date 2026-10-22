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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end: broker persists -> engine thread -> agreement writer thread -> H2. */
@SpringBootTest
class BrokerLogicTest {

    @Autowired
    private IBrokerLogic broker;
    @Autowired
    private ConsumerOrderRepository orders;
    @Autowired
    private ProviderOfferRepository offers;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void offerAndOrderArePersistedAgreeAndTheAgreementIsPersisted() {
        Submission offer = broker.acceptOffer(new OfferDto(usd("10.00"), usd("12.50"),
                null, "provider", "model", 1_000_000L));
        assertThat(offer.result().join()).isInstanceOf(MatchResult.Resting.class);

        Submission order = broker.acceptOrder(new OrderDto(usd("11.00"), usd("13.00"),
                Duration.ofMinutes(5), "provider", "model"));
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
        Submission order = broker.acceptOrder(new OrderDto(usd("1.00"), usd("1.00"), null, "provider", "model"));
        Submission offer = broker.acceptOffer(new OfferDto(usd("50.00"), usd("50.00"),
                null, "provider", "model", 500L));

        assertThat(orders.findById(order.id())).isPresent();
        ProviderOffer saved = offers.findById(offer.id()).orElseThrow();
        assertThat(saved.getAvailableTokens()).isEqualTo(500L);
        assertThat(saved.getTotalTokens()).isEqualTo(500L);
        assertThat(order.result().join()).isInstanceOf(MatchResult.Resting.class);
        assertThat(offer.result().join()).isInstanceOf(MatchResult.Resting.class);
    }

    @Test
    void orderWithoutPricesIsPersistedButRejected() {
        Submission order = broker.acceptOrder(new OrderDto(null, null, null, null, null));

        assertThat(orders.findById(order.id())).isPresent();
        assertThat(order.result().join()).isInstanceOf(MatchResult.Rejected.class);
    }

    private static MoneyDto usd(String amount) {
        return new MoneyDto(new BigDecimal(amount), "USD");
    }
}
