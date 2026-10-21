package com.vacantagent.market.matching.entities;

import com.vacantagent.market.entities.ConsumerOrder;

import java.math.RoundingMode;

import static com.vacantagent.market.matching.entities.Prices.toMicros;

/**
 * A consumer's order as held in the {@link OrderBook}: the maximum price per million tokens it accepts
 * for input and for output, in micro units (see {@link Prices}).
 *
 * @param order the persisted order; becomes part of the {@code Agreement} when matched
 */
public record BookOrder(ConsumerOrder order, long maxInput, long maxOutput) {
    /** Rounds limits down, so rounding can never produce a pair that does not fit exactly. */
    public static BookOrder from(ConsumerOrder order) {
        return new BookOrder(order,
                toMicros(order.getMaxCostPerMillionInputTokens(), RoundingMode.FLOOR),
                toMicros(order.getMaxCostPerMillionOutputTokens(), RoundingMode.FLOOR));
    }

    public long id() { return order.getId(); }

    public long total() { return maxInput + maxOutput; }

    /** Price fit on both legs. Business restrictions live in {@link com.vacantagent.market.logic.MarketRules}. */
    public boolean acceptsPrices(BookOffer offer) {
        return offer.priceInput() <= maxInput && offer.priceOutput() <= maxOutput;
    }
}