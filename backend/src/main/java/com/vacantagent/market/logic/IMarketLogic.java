package com.vacantagent.market.logic;

import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.matching.MatchResult;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point to the market for both sides. Safe to call from any thread. Orders and offers are persisted
 * before they enter the market. A {@code Matched} result completes only once its agreement is persisted,
 * so the agreement carries its id.
 */
public interface IMarketLogic {
    /** @param appUserId owner of the new order, the authenticated user */
    Submission acceptOrder(OrderDto order, long appUserId);

    /**
     * Like {@link #acceptOrder}, but the result completes only when the order's wait ends: {@code Matched} once the
     * agreement is persisted (on arrival or later), {@code Rejected} if refused or its wait time is over,
     * {@code Cancelled} if cancelled. Fails if the agreement could not be persisted or the market stopped.
     * An order without wait time waits until matched or cancelled, so callers bound the wait and cancel.
     */
    Submission acceptOrderAndAwaitMatch(OrderDto order, long appUserId);

    CompletableFuture<MatchResult> cancelOrder(long orderId);

    /** False if the order does not exist or belongs to someone else. */
    boolean isOrderOwnedBy(long orderId, long appUserId);

    /** @param appUserId owner of the new offer, the authenticated user */
    Submission acceptOffer(OfferDto offer, long appUserId);

    CompletableFuture<MatchResult> cancelOffer(long offerId);

    /** False if the offer does not exist or belongs to someone else. */
    boolean isOfferOwnedBy(long offerId, long appUserId);

    /** False if the agent does not exist or belongs to someone else. */
    boolean isAgentOwnedBy(long agentId, long appUserId);

    /** Sends an already persisted offer back into the market, e.g. after its agreement became idle. */
    CompletableFuture<MatchResult> resubmitOffer(ProviderOffer offer);

    /** Removes expired waiting entries from the book; completes with {@code Expired(0, 0)} if the market is stopped. */
    CompletableFuture<MatchResult> expireEntries();

    /**
     * Time the book was restored from the database, {@code null} before startup. Offers of agreements idle at this
     * time came back with the restore; later ones are released by the {@code AgreementReaper}.
     */
    OffsetDateTime restoredAt();

    /**
     * Records a successful call through the agreement on its consumer order, which keeps the agreement from
     * becoming idle.
     *
     * @return false if the agreement does not exist or is already idle (no call for the idle timeout), the call
     *         must then be refused
     */
    boolean recordAgreementSuccess(long agreementId);

    /**
     * Newest agreement of the consumer that may serve a call now: still valid, not idle, its offer has tokens left.
     * Its offer and the offer's provider are loaded.
     *
     * @param modelProvider only offers of this provider, {@code null} for any
     * @param modelName     only offers of this model, {@code null} for any
     */
    Optional<Agreement> findActiveAgreement(long consumerAppUserId, @Nullable String modelProvider,
                                            @Nullable String modelName);

    /**
     * Records a successful call on the offer: subtracts {@code tokens} from its available tokens (never below zero,
     * unlimited stays unlimited) and sets {@code lastSuccessAt}. Must run inside the caller's transaction.
     *
     * @return the offer as updated
     */
    ProviderOffer recordOfferSuccess(long offerId, long tokens, OffsetDateTime at);

    /** Records a failed call on the offer: sets {@code lastErrorAt}. Must run inside the caller's transaction. */
    void recordOfferError(long offerId, OffsetDateTime at);
}