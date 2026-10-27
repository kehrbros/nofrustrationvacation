package com.vacantagent.market.logic;

import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.common.money.Money;
import com.vacantagent.market.config.MarketProperties;
import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.data.ProviderOfferRepository;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.user.logic.AppUserProvisioning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import static com.vacantagent.market.MarketFixtures.emptyOrder;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offers of agreements that became idle re-enter the shared engine, each exactly once.
 *
 * <p>The scheduled reaper of the context runs on the real clock. Each test builds its own reaper with a clock some
 * hours ahead and agreements that only become idle then, so the scheduled reaper never sees them; the different
 * offsets keep the tests' windows apart.
 */
@SpringBootTest
class AgreementReaperTest {

    @Autowired
    private IMarketLogic market;
    @Autowired
    private AgreementRepository agreements;
    @Autowired
    private ProviderOfferRepository offers;
    @Autowired
    private ConsumerOrderRepository orders;
    @Autowired
    private MarketProperties props;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private AgentLogic agentLogic;
    @Autowired
    private AppUserProvisioning appUserProvisioning;

    /** Offers reference an existing agent (foreign key). */
    private long agentId;

    @BeforeEach
    void setUp() {
        long appUserId = appUserProvisioning.findOrCreate("reaper-user", null, null).getId();
        agentId = agentLogic.create(appUserId, "model", "provider", null, "sk-test-12345678").getId();
    }

    @Test
    void idleAgreementReleasesItsOfferExactlyOnce() {
        AgreementReaper reaper = reaperAhead(Duration.ofHours(1));
        OffsetDateTime base = now(reaper);
        startWindowAt(reaper, base.minusSeconds(10));
        Agreement agreement = agreement(base.minus(props.agreementIdleTimeout()).minusSeconds(5), null);   // idle at base - 5s

        assertThat(reaper.sweep()).isEqualTo(1);
        assertThat(reaper.sweep()).isZero();

        assertThat(market.cancelOffer(agreement.getProviderOffer().getId()).join())
                .isInstanceOf(MatchResult.Cancelled.class);
    }

    @Test
    void agreementIdleBeforeTheWindowIsNotReleased() {
        AgreementReaper reaper = reaperAhead(Duration.ofHours(3));
        OffsetDateTime base = now(reaper);
        startWindowAt(reaper, base);
        Agreement agreement = agreement(base.minusMinutes(2), null);      // idle long before the window: restore's job

        assertThat(reaper.sweep()).isZero();

        assertThat(market.cancelOffer(agreement.getProviderOffer().getId()).join())
                .isInstanceOf(MatchResult.NotFound.class);
    }

    @Test
    void recentlyAgreedOrUsedAgreementStays() {
        AgreementReaper reaper = reaperAhead(Duration.ofHours(5));
        OffsetDateTime base = now(reaper);
        startWindowAt(reaper, base.minusSeconds(10));
        Agreement fresh = agreement(base.minusSeconds(5), null);
        Agreement used = agreement(base.minus(props.agreementIdleTimeout()).minusSeconds(5), base.minusSeconds(5));

        assertThat(reaper.sweep()).isZero();

        assertThat(market.cancelOffer(fresh.getProviderOffer().getId()).join()).isInstanceOf(MatchResult.NotFound.class);
        assertThat(market.cancelOffer(used.getProviderOffer().getId()).join()).isInstanceOf(MatchResult.NotFound.class);
    }

    private AgreementReaper reaperAhead(Duration offset) {
        return new AgreementReaper(agreements, orders, market, props, transactionTemplate,
                Clock.offset(Clock.systemUTC(), offset));
    }

    private static OffsetDateTime now(AgreementReaper reaper) {
        return OffsetDateTime.now((Clock) ReflectionTestUtils.getField(reaper, "clock"));
    }

    private static void startWindowAt(AgreementReaper reaper, OffsetDateTime releasedUpTo) {
        ReflectionTestUtils.setField(reaper, "releasedUpTo", releasedUpTo);
    }

    /** Priced above every order other tests leave in the shared engine, so the re-entered offer stays waiting. */
    private Agreement agreement(OffsetDateTime agreedAt, OffsetDateTime lastSuccessAt) {
        Money price = Money.of("900.00");
        ProviderOffer offer = offers.save(new ProviderOffer(price, price, null, agentId, "provider", "model",
                null, null, 1000L, 1000L, null));
        ConsumerOrder order = emptyOrder();
        if (lastSuccessAt != null) order.recordSuccess(lastSuccessAt);
        return agreements.save(new Agreement(orders.save(order), offer, agreedAt, null, price, price));
    }
}