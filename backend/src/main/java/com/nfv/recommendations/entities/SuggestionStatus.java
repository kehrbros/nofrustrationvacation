package com.nfv.recommendations.entities;

/** Lifecycle of a {@link SuggestionSet}, see Docs/recommendations.md#auslöser-und-lebenszyklus. */
public enum SuggestionStatus {
    CURRENT,
    /** Route or incident changed since, the suggestions may no longer fit. */
    STALE,
    /** One of the suggestions was applied. */
    APPLIED,
    /** Past {@code expiresAt}. */
    EXPIRED
}