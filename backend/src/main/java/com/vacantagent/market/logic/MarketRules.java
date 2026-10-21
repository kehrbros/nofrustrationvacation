package com.vacantagent.market.logic;

import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import com.vacantagent.market.engine.QuadrantIndex;

/**
 * Business rules as plain boolean checks. No I/O: the matcher calls {@link #isValid} and {@link #canMatch}
 * inside its hot loop, so they must be fast and side-effect free. Anything that needs a database lookup
 * (blocked parties, credit limits) should be loaded into memory and refreshed, not queried here.
 *
 * <p>Price fit (offer price <= consumer max on both legs) is handled by the strategy and index.
 * {@link #canMatch} can only add restrictions on top of it, never loosen it.
 *
 * <p>Created as a bean in {@code MarketConfig}.
 */
public class MarketRules {

    private final long maxPrice;

    public MarketRules(long maxPrice) {
        if (maxPrice <= 0 || maxPrice >= QuadrantIndex.MAX_COORD) {
            throw new IllegalArgumentException("maxPrice must be between 1 and " + (QuadrantIndex.MAX_COORD - 1));
        }
        this.maxPrice = maxPrice;
    }

    /** Once the provider has failed, the offer is out of the market for good. */
    public boolean isProviderOfferAvailable(ProviderOffer providerOffer) {
        return providerOffer.getLastErrorAt() == null;
    }

    /** May this offer enter the market at all? */
    public boolean isValid(BookOffer offer) {
        return isProviderOfferAvailable(offer.offer())
                && isValidPrice(offer.priceInput()) && isValidPrice(offer.priceOutput());
    }

    /** May this order enter the market at all? */
    public boolean isValid(BookOrder order) {
        return isValidPrice(order.maxInput()) && isValidPrice(order.maxOutput());
    }

    /** May this specific order be paired with this specific offer? Prices already fit when this is called. */
    public boolean canMatch(BookOrder order, BookOffer offer) {
        return true;
    }

    protected boolean isValidPrice(long price) {
        return price > 0 && price <= maxPrice;
    }
}