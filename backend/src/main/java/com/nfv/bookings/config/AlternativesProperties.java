package com.nfv.bookings.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Search and booking of alternative accommodations via LiteAPI (Nuitee), paid with PayPal, see
 * Docs/accommodation-alternatives.md.
 *
 * @param apiKey           LiteAPI key ({@code LITEAPI_API_KEY}), sandbox keys start with {@code sand_}; empty: the
 *                         search is switched off
 * @param searchUrl        API base for rates and hotel data, e.g. {@code https://api.liteapi.travel/v3.0}
 * @param bookUrl          API base for prebook and book, e.g. {@code https://book.liteapi.travel/v3.0}
 * @param feePercent       our commission on top of the net rate, in percent
 * @param defaultRadius    radius around the accommodation in meters if the user does not change it
 * @param maxCandidates    candidates shown and passed to the assistant for ranking
 * @param guestNationality ISO 3166-1 alpha-2, LiteAPI prices depend on it
 * @param frontendUrl      base of the PayPal return URLs
 * @param timeout          per request to LiteAPI
 * @param searchesPerHour  per user, every search costs at LiteAPI
 */
@ConfigurationProperties("nfv.bookings.alternatives")
public record AlternativesProperties(String apiKey, String searchUrl, String bookUrl, BigDecimal feePercent,
                                     int defaultRadius, int maxCandidates, String guestNationality,
                                     String frontendUrl, Duration timeout, int searchesPerHour) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "AlternativesProperties[apiKey=" + (isConfigured() ? "<redacted>" : "<missing>") + ", searchUrl="
                + searchUrl + ", bookUrl=" + bookUrl + ", feePercent=" + feePercent + "]";
    }
}
