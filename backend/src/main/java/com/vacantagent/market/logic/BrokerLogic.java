package com.vacantagent.market.logic;

import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.data.ProviderOfferRepository;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.engine.MatchingEngine;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.mapper.OfferMapper;
import com.vacantagent.market.mapper.OrderMapper;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * Persists incoming orders and offers, then hands them to the {@link MatchingEngine}. Persisting first means
 * agreements always reference existing rows and waiting entries are reloaded after a restart.
 * Each match yields an {@code Agreement}; the result completes once the engine's sink has persisted it.
 */
@Service
@RequiredArgsConstructor
public class BrokerLogic implements IBrokerLogic {

    private final MatchingEngine engine;
    private final ConsumerOrderRepository orders;
    private final ProviderOfferRepository offers;
    private final AgreementRepository agreements;
    private final OrderMapper orderMapper;
    private final OfferMapper offerMapper;
    private final Clock clock;

    @Override
    public Submission acceptOrder(OrderDto dto, long appUserId) {
        ConsumerOrder order = orders.save(orderMapper.toEntity(dto, appUserId));
        return new Submission(order.getId(), engine.submit(new MatchingEngine.SubmitOrder(BookOrder.from(order))));
    }

    @Override
    public CompletableFuture<MatchResult> cancelOrder(long orderId) {
        return engine.submit(new MatchingEngine.CancelOrder(orderId));
    }

    @Override
    public boolean isOrderOwnedBy(long orderId, long appUserId) {
        return orders.existsByIdAndAppUserId(orderId, appUserId);
    }

    @Override
    public Submission acceptOffer(OfferDto dto, long appUserId) {
        ProviderOffer offer = offers.save(offerMapper.toEntity(dto, appUserId));
        return new Submission(offer.getId(), engine.submit(new MatchingEngine.SubmitOffer(BookOffer.from(offer))));
    }

    @Override
    public CompletableFuture<MatchResult> cancelOffer(long offerId) {
        return engine.submit(new MatchingEngine.CancelOffer(offerId));
    }

    @Override
    public boolean isOfferOwnedBy(long offerId, long appUserId) {
        return offers.existsByIdAndAppUserId(offerId, appUserId);
    }

    @Override
    public boolean recordAgreementSuccess(long agreementId) {
        return agreements.markSuccess(agreementId, OffsetDateTime.now(clock)) == 1;
    }
}
