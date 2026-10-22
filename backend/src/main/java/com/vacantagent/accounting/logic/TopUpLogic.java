package com.vacantagent.accounting.logic;

import com.vacantagent.accounting.config.AccountingProperties;
import com.vacantagent.accounting.data.TopUpRepository;
import com.vacantagent.accounting.entities.AccountType;
import com.vacantagent.accounting.entities.EntryType;
import com.vacantagent.accounting.entities.TopUp;
import com.vacantagent.accounting.entities.TopUpStatus;
import com.vacantagent.common.errorhandling.TechnicalException;
import com.vacantagent.common.money.Money;
import com.vacantagent.common.money.OriginalAmount;
import com.vacantagent.paypal.PayPalCheckout;
import com.vacantagent.paypal.PayPalException;
import com.vacantagent.paypal.PayPalRejectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * Consumers top up their {@link AccountType#CREDIT} account via PayPal: start (PayPal order), the consumer approves
 * at PayPal, capture. Credited once the capture is completed, see Docs/accounting.md.
 * <p>
 * PayPal is called outside of database transactions, so a slow PayPal holds no locks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopUpLogic {
    private final TopUpRepository topUpRepository;
    private final AccountLedger ledger;
    private final PayPalCheckout checkout;
    private final AccountingProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public record StartedTopUp(TopUp topUp, String approveUrl) {
    }

    /**
     * @param amount credited amount in the base currency
     * @param paid   amount the consumer pays at PayPal, with the exchange rate used for {@code amount}
     */
    public StartedTopUp start(long appUserId, Money amount, OriginalAmount paid) {
        if (amount.isLessThan(properties.minTopUp()) || amount.isGreaterThan(properties.maxTopUp())) {
            throw new AmountOutOfRangeException("Top-up must be between " + properties.minTopUp() + " and " + properties.maxTopUp());
        }
        TopUp created = topUpRepository.save(new TopUp(appUserId, amount, paid, now()));
        PayPalCheckout.CheckoutOrder order;
        try {
            order = checkout.createOrder("top-up-" + created.getId(), PayPalAmounts.of(paid), "VacantAgent credit");
        } catch (PayPalException e) {
            update(created.getId(), topUp -> topUp.fail("PayPal order not created: " + e.getMessage(), now()));
            throw e;
        }
        TopUp topUp = update(created.getId(), t -> t.orderCreated(order.orderId()));
        return new StartedTopUp(topUp, order.approveUrl());
    }

    /**
     * Captures the PayPal order after the consumer approved it. Idempotent: repeating returns the current state.
     *
     * @throws PaymentDeclinedException if PayPal refuses the capture (not approved, declined funding source)
     */
    public TopUp capture(long appUserId, String paypalOrderId) {
        TopUp topUp = find(appUserId, paypalOrderId);
        if (topUp.isFinal() || topUp.getStatus() == TopUpStatus.PENDING) {
            return topUp;
        }
        PayPalCheckout.OrderState state;
        try {
            state = checkout.capture(paypalOrderId, "capture-top-up-" + topUp.getId());
        } catch (PayPalRejectedException e) {
            if ("ORDER_NOT_APPROVED".equals(e.getIssue())) {
                throw new PaymentDeclinedException("PAYMENT_NOT_APPROVED", "Payment not approved at PayPal yet");
            }
            update(topUp.getId(), t -> t.fail("Capture refused: " + e.getIssue(), now()));
            throw new PaymentDeclinedException("PAYMENT_DECLINED", "PayPal declined the payment: " + e.getIssue());
        }
        return apply(topUp.getId(), state);
    }

    /** The consumer cancelled at PayPal. A top-up already paid is not affected. */
    public TopUp cancel(long appUserId, String paypalOrderId) {
        TopUp topUp = find(appUserId, paypalOrderId);
        return update(topUp.getId(), t -> {
            if (t.getStatus() == TopUpStatus.CREATED) {
                t.cancel(now());
            }
        });
    }

    public List<TopUp> latest(long appUserId, int limit) {
        return topUpRepository.findByAppUserIdOrderByCreatedAtDescIdDesc(appUserId, PageRequest.of(0, limit));
    }

    /** Re-reads pending captures from PayPal, called by {@code PaymentStatusPoller}. */
    public void checkPending() {
        for (TopUp topUp : topUpRepository.findByStatus(TopUpStatus.PENDING)) {
            try {
                apply(topUp.getId(), checkout.getOrder(topUp.getPaypalOrderId()));
            } catch (RuntimeException e) {
                log.warn("Checking pending top-up {} failed", topUp.getId(), e);
            }
        }
    }

    /** Applies the PayPal state; credits exactly once, as the row is locked and the status checked. */
    TopUp apply(long topUpId, PayPalCheckout.OrderState state) {
        return update(topUpId, topUp -> {
            if (topUp.isFinal()) {
                return;
            }
            if (state.isCaptureCompleted()) {
                if (state.capturedAmount() == null || !PayPalAmounts.matches(topUp.getPaid(), state.capturedAmount())) {
                    throw new TechnicalException("Top-up " + topUpId + ": captured " + state.capturedAmount()
                            + " differs from " + topUp.getPaid() + ", check manually");
                }
                ledger.credit(topUp.getAppUserId(), AccountType.CREDIT, topUp.getAmount(), EntryType.TOP_UP, topUpId);
                topUp.complete(state.captureId(), now());
                log.info("Top-up {} of user {} completed: {}", topUpId, topUp.getAppUserId(), topUp.getAmount());
            } else if (state.isCapturePending()) {
                topUp.pending(state.captureId());
            } else if (state.captureStatus() != null) {
                topUp.fail("Capture " + state.captureStatus(), now());
            }
        });
    }

    private TopUp find(long appUserId, String paypalOrderId) {
        return topUpRepository.findByPaypalOrderIdAndAppUserId(paypalOrderId, appUserId)
                .orElseThrow(() -> new TopUpNotFoundException(paypalOrderId));
    }

    private TopUp update(long topUpId, Consumer<TopUp> change) {
        return transactionTemplate.execute(_ -> {
            TopUp topUp = topUpRepository.findByIdForUpdate(topUpId).orElseThrow();
            change.accept(topUp);
            return topUp;
        });
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
