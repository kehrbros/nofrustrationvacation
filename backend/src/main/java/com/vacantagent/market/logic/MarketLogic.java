package com.vacantagent.market.logic;

import com.vacantagent.market.config.MarketProperties;
import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.data.AgreementWriter;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.data.ProviderOfferRepository;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.engine.MatchingEngine;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.mapper.OfferMapper;
import com.vacantagent.market.mapper.OrderMapper;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * Coordinates the market around the {@link MatchingEngine}, which only matches:
 * <ul>
 *   <li>persists incoming orders and offers before they enter the engine, so agreements always reference existing
 *       rows and waiting entries are reloaded after a restart;</li>
 *   <li>hands every agreement the engine produces to the {@link AgreementWriter}; a {@code Matched} result completes
 *       only once its agreement is persisted;</li>
 *   <li>restores waiting offers and orders at startup, then starts the engine, and stops it on shutdown.</li>
 * </ul>
 * Every command reaches the engine through {@link #submit}, also those of {@code AgreementReaper} and
 * {@code BookExpirySweeper}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLogic implements IMarketLogic {

    private final MatchingEngine engine;
    private final AgreementWriter writer;
    private final MarketRules rules;
    private final ConsumerOrderRepository orders;
    private final ProviderOfferRepository offers;
    private final AgreementRepository agreements;
    private final OrderMapper orderMapper;
    private final OfferMapper offerMapper;
    private final MarketProperties props;
    private final Clock clock;

    private volatile OffsetDateTime restoredAt;

    // ------------------------------------------------------------------ lifecycle

    /**
     * Waiting offers and orders are reloaded before the matching thread starts. Offers of agreements idle at
     * {@link #restoredAt} come back here; the {@code AgreementReaper} releases those that become idle later.
     */
    @PostConstruct
    void start() {
        restoredAt = OffsetDateTime.now(clock);
        restoreOffers(engine, offers, rules, restoredAt, props.agreementIdleTimeout());
        restoreOrders(engine, orders, rules);
        engine.start();
    }

    /**
     * Stops matching first. This bean depends on the writer, so Spring destroys the writer afterwards and it can
     * flush the last agreements.
     */
    @PreDestroy
    void stop() throws InterruptedException {
        engine.stop();
    }

    // ------------------------------------------------------------------ API

    @Override
    public Submission acceptOrder(OrderDto dto, long appUserId) {
        ConsumerOrder order = orders.save(orderMapper.toEntity(dto, appUserId));
        return new Submission(order.getId(), submit(new MatchingEngine.SubmitOrder(BookOrder.from(order))));
    }

    @Override
    public CompletableFuture<MatchResult> cancelOrder(long orderId) {
        return submit(new MatchingEngine.CancelOrder(orderId));
    }

    @Override
    public boolean isOrderOwnedBy(long orderId, long appUserId) {
        return orders.existsByIdAndAppUserId(orderId, appUserId);
    }

    @Override
    public Submission acceptOffer(OfferDto dto, long appUserId) {
        ProviderOffer offer = offers.save(offerMapper.toEntity(dto, appUserId));
        return new Submission(offer.getId(), submit(new MatchingEngine.SubmitOffer(BookOffer.from(offer))));
    }

    @Override
    public CompletableFuture<MatchResult> cancelOffer(long offerId) {
        return submit(new MatchingEngine.CancelOffer(offerId));
    }

    @Override
    public boolean isOfferOwnedBy(long offerId, long appUserId) {
        return offers.existsByIdAndAppUserId(offerId, appUserId);
    }

    @Override
    public CompletableFuture<MatchResult> resubmitOffer(ProviderOffer offer) {
        return submit(new MatchingEngine.SubmitOffer(BookOffer.from(offer)));
    }

    @Override
    public CompletableFuture<MatchResult> expireEntries() {
        if (!engine.isRunning()) return CompletableFuture.completedFuture(new MatchResult.Expired(0, 0));
        return submit(new MatchingEngine.ExpireEntries(rules.nowMillis()));
    }

    @Override
    public OffsetDateTime restoredAt() {
        return restoredAt;
    }

    /** Locks the order, so a concurrent release by the {@code AgreementReaper} waits or sees this call. */
    @Override
    @Transactional
    public boolean recordAgreementSuccess(long agreementId) {
        ConsumerOrder order = orders.findForUpdateByAgreementId(agreementId).orElse(null);
        if (order == null) return false;
        Agreement agreement = agreements.findById(agreementId).orElseThrow();   // shares the locked, fresh order
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (agreement.isIdleSince(now.minus(props.agreementIdleTimeout()))) return false;
        order.recordSuccess(now);
        return true;
    }

    // ------------------------------------------------------------------ coordination

    /**
     * Sends a command to the engine. A {@code Matched} result is handed to the writer and completes once the
     * agreement is persisted, so callers see its id; if persisting fails, the future fails although the pair has
     * already left the book. The hand-off runs on the engine thread and does not block it.
     */
    private CompletableFuture<MatchResult> submit(MatchingEngine.Command command) {
        return engine.submit(command).thenCompose(result -> result instanceof MatchResult.Matched matched
                ? writer.enqueue(matched.agreement()).thenApply(stored -> result)
                : CompletableFuture.completedFuture(result));
    }

    /**
     * Loads every available offer (tokens left, no agreement active at {@code restoredAt}), oldest first. Offers that
     * have expired, or that {@link MarketRules#isValid} refuses (provider failed, invalid prices), are skipped,
     * as the strategy would reject them.
     */
    static void restoreOffers(MatchingEngine engine, ProviderOfferRepository offers, MarketRules rules,
                              OffsetDateTime restoredAt, Duration agreementIdleTimeout) {
        long now = rules.nowMillis();
        int restored = 0;
        int expired = 0;
        for (var offer : offers.findAllAvailable(restoredAt, restoredAt.minus(agreementIdleTimeout))) {
            BookOffer bookOffer = BookOffer.from(offer);
            if (rules.isExpired(bookOffer, now)) {
                expired++;
                continue;
            }
            if (!rules.isValid(bookOffer)) {
                log.warn("Not restoring offer {}: violates market rules", offer.getId());
                continue;
            }
            engine.restoreOffer(bookOffer);
            restored++;
        }
        log.info("Restored {} waiting offers, skipped {} expired", restored, expired);
    }

    /**
     * Loads every order without an agreement, oldest first. Orders whose wait time is over, or that
     * {@link MarketRules#isValid} refuses (invalid prices), are skipped, as the strategy would reject them.
     * So is a second order of a user for the same provider and model (e.g. one was cancelled, which is not
     * persisted); the older one is restored.
     */
    static void restoreOrders(MatchingEngine engine, ConsumerOrderRepository orders, MarketRules rules) {
        long now = rules.nowMillis();
        int restored = 0;
        int expired = 0;
        int duplicates = 0;
        for (var order : orders.findAllWithoutAgreement()) {
            BookOrder bookOrder = BookOrder.from(order);
            if (rules.isExpired(bookOrder, now)) {
                expired++;
                continue;
            }
            if (!rules.isValid(bookOrder)) {
                log.warn("Not restoring order {}: violates market rules", order.getId());
                continue;
            }
            if (!engine.restoreOrder(bookOrder)) {
                log.warn("Not restoring order {}: its user already has an order waiting for this model", order.getId());
                duplicates++;
                continue;
            }
            restored++;
        }
        log.info("Restored {} waiting orders, skipped {} expired and {} duplicates", restored, expired, duplicates);
    }
}