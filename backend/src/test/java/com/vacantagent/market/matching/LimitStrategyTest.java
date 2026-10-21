package com.vacantagent.market.matching;

import com.vacantagent.common.money.Money;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.logic.MarketRules;
import com.vacantagent.market.matching.MatchResult.Matched;
import com.vacantagent.market.matching.MatchResult.Rejected;
import com.vacantagent.market.matching.MatchResult.Resting;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import com.vacantagent.market.matching.entities.OrderBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

import static com.vacantagent.market.MarketFixtures.bookOffer;
import static com.vacantagent.market.MarketFixtures.bookOrder;
import static com.vacantagent.market.MarketFixtures.emptyOffer;
import static com.vacantagent.market.MarketFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests: no Spring, no threads, no database. Each test builds a fresh book. */
class LimitStrategyTest {

    private OrderBook book;
    private LimitStrategy strategy;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
        strategy = new LimitStrategy(new MarketRules(100_00));
    }

    @Test
    void orderTakesCheapestOfferThatFitsBothLegs() {
        offer(1, 10_00, 12_50);   // total 22.50
        offer(2, 9_00, 13_50);    // total 22.50, newer
        offer(3, 6_00, 14_00);    // total 20.00, output too high below

        assertThat(pairOf(order(100, 11_00, 13_00))).isEqualTo(matched(100, 1));   // 3 and 2 fail on output
        assertThat(pairOf(order(101, 10_00, 15_00))).isEqualTo(matched(101, 3));   // cheapest total
        assertThat(book.offerCount()).isEqualTo(1);
    }

    @Test
    void orderWaitsWhenNothingFitsAndIsFilledByALaterOffer() {
        offer(2, 9_00, 13_50);

        assertThat(order(102, 8_00, 14_00)).isInstanceOf(Resting.class);   // input too high
        assertThat(pairOf(offer(4, 7_50, 13_00))).isEqualTo(matched(102, 4));
        assertThat(book.orderCount()).isZero();
    }

    @Test
    void offerGoesToHighestPayingOrderThenOldest() {
        order(200, 10_00, 10_00);   // total 20
        order(201, 15_00, 15_00);   // total 30
        order(202, 14_00, 16_00);   // total 30, newer

        assertThat(pairOf(offer(1, 9_00, 9_00))).isEqualTo(matched(201, 1));
        assertThat(pairOf(offer(2, 9_00, 9_00))).isEqualTo(matched(202, 2));
        assertThat(pairOf(offer(3, 9_00, 9_00))).isEqualTo(matched(200, 3));
    }

    @Test
    void offerSkipsOrdersThatRejectOneLeg() {
        order(200, 20_00, 5_00);    // highest total, but output limit 5
        order(201, 10_00, 10_00);

        assertThat(pairOf(offer(1, 9_00, 9_00))).isEqualTo(matched(201, 1));
        assertThat(book.hasOrder(200)).isTrue();
    }

    @Test
    void rulesCanBlockAPairingAndTheNextBestIsUsed() {
        strategy = new LimitStrategy(new MarketRules(100_00) {
            @Override
            public boolean canMatch(BookOrder order, BookOffer offer) {
                return offer.id() != 1;
            }
        });
        offer(1, 5_00, 5_00);
        offer(2, 6_00, 6_00);

        assertThat(pairOf(order(100, 10_00, 10_00))).isEqualTo(matched(100, 2));
        assertThat(book.hasOffer(1)).isTrue();
    }

    @Test
    void customRulesArePluggable() {
        strategy = new LimitStrategy(new MarketRules(100_00) {
            @Override
            public boolean canMatch(BookOrder order, BookOffer offer) {
                return false;
            }
        });
        offer(1, 5_00, 5_00);

        assertThat(order(100, 10_00, 10_00)).isInstanceOf(Resting.class);
    }

    @Test
    void agreementBillsTheOffersExactPricesUntilTheOfferEnds() {
        ProviderOffer entity = withId(emptyOffer(), 1);
        OffsetDateTime offeredUntil = OffsetDateTime.now().plusDays(1);
        ReflectionTestUtils.setField(entity, "pricePerMillionInputTokens", Money.of("9.0000005"));
        ReflectionTestUtils.setField(entity, "pricePerMillionOutputTokens", Money.of("12.50"));
        ReflectionTestUtils.setField(entity, "offeredUntil", offeredUntil);
        strategy.onOffer(new BookOffer(entity, 9_00, 12_50), book);

        Agreement agreement = ((Matched) order(100, 10_00, 13_00)).agreement();

        assertThat(agreement.getConsumerOrder().getId()).isEqualTo(100);
        assertThat(agreement.getProviderOffer()).isSameAs(entity);
        assertThat(agreement.getAgreedPricePerMillionInputTokens()).isEqualTo(Money.of("9.0000005"));
        assertThat(agreement.getAgreedPricePerMillionOutputTokens()).isEqualTo(Money.of("12.50"));
        assertThat(agreement.getValidUntil()).isEqualTo(offeredUntil);
        assertThat(agreement.getAgreedAt()).isNotNull();
    }

    @Test
    void invalidEntriesAreRejectedAndNotStored() {
        assertThat(offer(1, 0, 5_00)).isInstanceOf(Rejected.class);
        assertThat(order(100, 5_00, 200_00)).isInstanceOf(Rejected.class);
        assertThat(book.offerCount()).isZero();
        assertThat(book.orderCount()).isZero();
    }

    @Test
    void duplicateWaitingIdIsRejected() {
        offer(1, 50_00, 50_00);
        assertThat(offer(1, 60_00, 60_00)).isInstanceOf(Rejected.class);
    }

    private MatchResult offer(long id, long priceInput, long priceOutput) {
        return strategy.onOffer(bookOffer(id, priceInput, priceOutput), book);
    }

    private MatchResult order(long id, long maxInput, long maxOutput) {
        return strategy.onOrder(bookOrder(id, maxInput, maxOutput), book);
    }

    /** Expected pairing as (orderId, offerId); compared against the agreement inside a Matched result. */
    private static Pair matched(long orderId, long offerId) {
        return new Pair(orderId, offerId);
    }

    private record Pair(long orderId, long offerId) {}

    private static Pair pairOf(MatchResult result) {
        assertThat(result).isInstanceOf(Matched.class);
        Agreement a = ((Matched) result).agreement();
        return new Pair(a.getConsumerOrder().getId(), a.getProviderOffer().getId());
    }
}