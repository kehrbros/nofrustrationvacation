package com.vacantagent.market.logic;

import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.matching.MatchResult;

import java.util.concurrent.CompletableFuture;

/**
 * Entry point to the market for both sides. Safe to call from any thread. Orders and offers are persisted
 * before they enter the market. A {@code Matched} result completes only once its agreement is persisted,
 * so the agreement carries its id.
 */
public interface IBrokerLogic {
    /** @param appUserId owner of the new order, the authenticated user */
    Submission acceptOrder(OrderDto order, long appUserId);

    CompletableFuture<MatchResult> cancelOrder(long orderId);

    /** False if the order does not exist or belongs to someone else. */
    boolean isOrderOwnedBy(long orderId, long appUserId);

    /** @param appUserId owner of the new offer, the authenticated user */
    Submission acceptOffer(OfferDto offer, long appUserId);

    CompletableFuture<MatchResult> cancelOffer(long offerId);

    /** False if the offer does not exist or belongs to someone else. */
    boolean isOfferOwnedBy(long offerId, long appUserId);

    /**
     * Records a successful call through the agreement, which keeps it from being closed as unused.
     *
     * @return false if the agreement does not exist or was closed, the call must then be refused
     */
    boolean recordAgreementSuccess(long agreementId);
}