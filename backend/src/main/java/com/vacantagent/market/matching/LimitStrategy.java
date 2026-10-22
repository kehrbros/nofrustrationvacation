package com.vacantagent.market.matching;

import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.logic.MarketRules;
import com.vacantagent.market.matching.MatchResult.Matched;
import com.vacantagent.market.matching.MatchResult.Rejected;
import com.vacantagent.market.matching.MatchResult.Resting;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import com.vacantagent.market.matching.entities.OrderBook;

import java.time.OffsetDateTime;

/**
 * Limit matching:
 * <ul>
 *   <li>An entry is checked against {@link MarketRules} first and rejected if invalid.</li>
 *   <li>It is paired with the best counterpart whose prices fit on both legs and which the rules allow:
 *       a new order gets the cheapest offer, a new offer gets the highest-paying order,
 *       oldest first on equal totals.</li>
 *   <li>If nothing fits, it waits in the book until a counterpart arrives. An order whose wait time is
 *       already over is rejected instead.</li>
 *   <li>Waiting entries that have expired are skipped; {@code MatchingEngine.ExpireEntries} removes them.</li>
 * </ul>
 * Stateless apart from the rules, so one instance can serve any number of books.
 */
public final class LimitStrategy implements MatchingStrategy {

    private final MarketRules rules;

    public LimitStrategy(MarketRules rules) {
        this.rules = rules;
    }

    @Override
    public MatchResult onOrder(BookOrder order, OrderBook book) {
        if (!rules.isValid(order)) return new Rejected("Order violates market rules");
        if (book.hasOrder(order.id())) return new Rejected("Duplicate order id " + order.id());

        long now = rules.nowMillis();
        return book.bestOfferFor(order, offer -> !rules.isExpired(offer, now) && rules.canMatch(order, offer))
                .<MatchResult>map(offer -> {
                    book.removeOffer(offer.id());
                    return new Matched(agree(order, offer));
                })
                .orElseGet(() -> {
                    if (rules.isExpired(order, now)) return new Rejected("Order wait time is over");
                    book.addOrder(order);
                    return new Resting();
                });
    }

    @Override
    public MatchResult onOffer(BookOffer offer, OrderBook book) {
        if (!rules.isValid(offer)) return new Rejected("Offer violates market rules");
        if (book.hasOffer(offer.id())) return new Rejected("Duplicate offer id " + offer.id());

        long now = rules.nowMillis();
        return book.bestOrderFor(offer, order -> !rules.isExpired(order, now) && rules.canMatch(order, offer))
                .<MatchResult>map(order -> {
                    book.removeOrder(order.id());
                    return new Matched(agree(order, offer));
                })
                .orElseGet(() -> {
                    book.addOffer(offer);
                    return new Resting();
                });
    }

    /** The consumer is billed at the provider's exact prices; the agreement lasts as long as the offer. */
    private static Agreement agree(BookOrder order, BookOffer offer) {
        return new Agreement(order.order(), offer.offer(), OffsetDateTime.now(), offer.offer().getOfferedUntil(),
                offer.offer().getPricePerMillionInputTokens(), offer.offer().getPricePerMillionOutputTokens());
    }
}