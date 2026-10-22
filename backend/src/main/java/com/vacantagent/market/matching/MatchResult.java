package com.vacantagent.market.matching;

import com.vacantagent.market.entities.Agreement;

/** Outcome of one command sent to the matching engine. */
public sealed interface MatchResult {

    /** The incoming offer or order was paired immediately. The agreement is persisted asynchronously. */
    record Matched(Agreement agreement) implements MatchResult {}

    /** No counterpart fits yet; the entry now waits in the book. */
    record Resting() implements MatchResult {}

    /** The entry was refused and is not in the book. */
    record Rejected(String reason) implements MatchResult {}

    /** A waiting entry was removed by a cancel. */
    record Cancelled() implements MatchResult {}

    /** A cancel named an id that is not waiting (already matched, cancelled or unknown). */
    record NotFound() implements MatchResult {}

    /** Waiting entries removed because their time ran out. */
    record Expired(int offers, int orders) implements MatchResult {}
}