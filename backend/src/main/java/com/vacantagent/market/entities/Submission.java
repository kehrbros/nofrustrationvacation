package com.vacantagent.market.entities;

import com.vacantagent.market.matching.MatchResult;

import java.util.concurrent.CompletableFuture;

/**
 * An order or offer {@code MarketLogic} has persisted and handed to the matching engine.
 *
 * @param id     id of the persisted entity, e.g. to cancel it
 * @param result completes with the matching outcome; a {@code Matched} result once the agreement is persisted
 */
public record Submission(long id, CompletableFuture<MatchResult> result) {
}
