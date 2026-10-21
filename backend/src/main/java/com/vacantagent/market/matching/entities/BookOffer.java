package com.vacantagent.market.matching.entities;

import com.vacantagent.market.entities.ProviderOffer;

import java.math.RoundingMode;

import static com.vacantagent.market.matching.entities.Prices.toMicros;

/**
 * A provider's offer as held in the {@link OrderBook}: a price per million tokens for input and for output.
 * Prices are in micro units (see {@link Prices}) to avoid floating-point errors.
 *
 * @param offer the persisted offer; becomes part of the {@code Agreement} when matched
 */
public record BookOffer(ProviderOffer offer, long priceInput, long priceOutput) {
    /** Rounds prices up, so rounding can never produce a pair that does not fit exactly. */
    public static BookOffer from(ProviderOffer offer) {
        return new BookOffer(offer,
                toMicros(offer.getPricePerMillionInputTokens(), RoundingMode.CEILING),
                toMicros(offer.getPricePerMillionOutputTokens(), RoundingMode.CEILING));
    }

    public long id() { return offer.getId(); }

    public long total() { return priceInput + priceOutput; }
}