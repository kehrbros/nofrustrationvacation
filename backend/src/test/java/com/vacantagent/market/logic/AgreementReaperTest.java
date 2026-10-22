package com.vacantagent.market.logic;

import com.vacantagent.common.money.Money;
import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.data.ProviderOfferRepository;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.matching.MatchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static com.vacantagent.market.MarketFixtures.emptyOrder;
import static org.assertj.core.api.Assertions.assertThat;

/** Unused agreements are closed and their offer re-enters the shared engine. */
@SpringBootTest
class AgreementReaperTest {

    @Autowired
    private AgreementReaper reaper;
    @Autowired
    private IMarketLogic market;
    @Autowired
    private AgreementRepository agreements;
    @Autowired
    private ProviderOfferRepository offers;
    @Autowired
    private ConsumerOrderRepository orders;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void unusedAgreementIsClosedAndItsOfferReentersTheMarket() {
        Agreement agreement = agreement(OffsetDateTime.now().minusHours(1));

        reaper.sweep();

        Agreement closed = agreements.findById(agreement.getId()).orElseThrow();
        assertThat(closed.getLastErrorAt()).isNotNull();
        assertThat(market.cancelOffer(agreement.getProviderOffer().getId()).join())
                .isInstanceOf(MatchResult.Cancelled.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agreement_aud WHERE id = ?",
                Integer.class, agreement.getId())).isEqualTo(2);
    }

    @Test
    void closedAgreementRefusesCalls() {
        Agreement agreement = agreement(OffsetDateTime.now().minusHours(1));

        reaper.sweep();

        assertThat(market.recordAgreementSuccess(agreement.getId())).isFalse();
    }

    @Test
    void recentlyAgreedOrUsedAgreementStaysOpen() {
        Agreement fresh = agreement(OffsetDateTime.now());
        Agreement used = agreement(OffsetDateTime.now().minusHours(1));
        assertThat(market.recordAgreementSuccess(used.getId())).isTrue();

        reaper.sweep();

        assertThat(agreements.findById(fresh.getId()).orElseThrow().getLastErrorAt()).isNull();
        Agreement stillOpen = agreements.findById(used.getId()).orElseThrow();
        assertThat(stillOpen.getLastErrorAt()).isNull();
        assertThat(stillOpen.getLastSuccessAt()).isNotNull();
        assertThat(market.cancelOffer(used.getProviderOffer().getId()).join())
                .isInstanceOf(MatchResult.NotFound.class);
    }

    /** Priced above every order other tests leave in the shared engine, so the re-entered offer stays waiting. */
    private Agreement agreement(OffsetDateTime agreedAt) {
        Money price = Money.of("900.00");
        ProviderOffer offer = offers.save(new ProviderOffer(price, price, null, "provider", "model",
                null, null, 1000L, 1000L, null));
        return agreements.save(new Agreement(orders.save(emptyOrder()), offer, agreedAt, null, price, price));
    }
}