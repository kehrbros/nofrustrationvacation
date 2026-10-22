package com.vacantagent.market.matching.entities;

import com.vacantagent.market.entities.ProviderOffer;

import java.math.RoundingMode;
import java.time.OffsetDateTime;

import static com.vacantagent.market.matching.entities.Prices.toMicros;

/**
 * A provider's offer as held in the {@link OrderBook}: a price per million tokens for input and for output.
 * Prices are in micro units (see {@link Prices}) to avoid floating-point errors.
 *
 * @param offer     the persisted offer; becomes part of the {@code Agreement} when matched
 * @param expiresAt epoch millis of {@code offeredUntil}, or {@link OrderBook#NO_EXPIRY}; precomputed for the hot loop
 */
public record BookOffer(ProviderOffer offer, long priceInput, long priceOutput, long expiresAt) {

    public BookOffer(ProviderOffer offer, long priceInput, long priceOutput) {
        this(offer, priceInput, priceOutput, expiryOf(offer));
    }

    /** Rounds prices up, so rounding can never produce a pair that does not fit exactly. */
    public static BookOffer from(ProviderOffer offer) {
        return new BookOffer(offer,
                toMicros(offer.getPricePerMillionInputTokens(), RoundingMode.CEILING),
                toMicros(offer.getPricePerMillionOutputTokens(), RoundingMode.CEILING));
    }

    public long id() { return offer.getId(); }

    public long total() { return priceInput + priceOutput; }

    private static long expiryOf(ProviderOffer offer) {
        OffsetDateTime until = offer.getOfferedUntil();
        return until == null ? OrderBook.NO_EXPIRY : until.toInstant().toEpochMilli();
    }
}