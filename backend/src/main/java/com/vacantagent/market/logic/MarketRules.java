package com.vacantagent.market.logic;

import com.vacantagent.market.entities.Agent;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import com.vacantagent.market.engine.QuadrantIndex;

import java.time.Clock;

/**
 * Business rules as plain boolean checks. No I/O: the matcher calls {@link #isValid}, {@link #isExpired} and
 * {@link #canMatch} inside its hot loop, so they must be fast and side-effect free. Anything that needs a database
 * lookup (blocked parties, credit limits) should be loaded into memory and refreshed, not queried here.
 *
 * <p>Price fit (offer price <= consumer max on both legs) is handled by the strategy and index, expiry of waiting
 * entries by the strategy. {@link #canMatch} can only add restrictions on top of them, never loosen them.
 *
 * <p>Created as a bean in {@code MarketConfig}.
 */
public class MarketRules {

    private final long maxPrice;
    private final Clock clock;

    public MarketRules(long maxPrice) {
        this(maxPrice, Clock.systemUTC());
    }

    public MarketRules(long maxPrice, Clock clock) {
        if (maxPrice <= 0 || maxPrice >= QuadrantIndex.MAX_COORD) {
            throw new IllegalArgumentException("maxPrice must be between 1 and " + (QuadrantIndex.MAX_COORD - 1));
        }
        this.maxPrice = maxPrice;
        this.clock = clock;
    }

    /** Read once per incoming entry and passed to {@link #isExpired}, so the hot loop does not hit the clock. */
    public long nowMillis() {
        return clock.millis();
    }

    /** Once the provider has failed, the offer is out of the market for good. */
    public boolean isProviderOfferAvailable(ProviderOffer providerOffer) {
        return providerOffer.getLastErrorAt() == null;
    }

    /** May this offer enter the market at all? An expired offer has nothing left to sell. */
    public boolean isValid(BookOffer offer) {
        return isProviderOfferAvailable(offer.offer()) && !isExpired(offer, nowMillis())
                && isValidPrice(offer.priceInput()) && isValidPrice(offer.priceOutput());
    }

    /**
     * May this order enter the market at all? Expiry is not checked here: an order whose wait time is over
     * (e.g. zero) may still match on arrival, it just does not wait in the book.
     */
    public boolean isValid(BookOrder order) {
        return isValidPrice(order.maxInput()) && isValidPrice(order.maxOutput());
    }

    /** Past {@code offeredUntil}. */
    public boolean isExpired(BookOffer offer, long nowMillis) {
        return offer.expiresAt() <= nowMillis;
    }

    /** Past {@code createdAt + marketWaitTime}. */
    public boolean isExpired(BookOrder order, long nowMillis) {
        return order.expiresAt() <= nowMillis;
    }

    /**
     * May this specific order be paired with this specific offer? Prices already fit when this is called.
     * The order may leave provider and/or model empty to accept any; given values must match exactly.
     */
    public boolean canMatch(BookOrder order, BookOffer offer) {
        Agent agent = offer.offer().getAgent();
        return acceptsValue(order.order().getModelProvider(), agent.getModelProvider())
                && acceptsValue(order.order().getModelName(), agent.getModelName());
    }

    /** An order that leaves a field empty accepts any value for it. */
    private static boolean acceptsValue(String requested, String offered) {
        return requested == null || requested.equals(offered);
    }

    protected boolean isValidPrice(long price) {
        return price > 0 && price <= maxPrice;
    }
}