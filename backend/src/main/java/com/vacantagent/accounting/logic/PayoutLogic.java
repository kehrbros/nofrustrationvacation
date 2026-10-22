package com.vacantagent.accounting.logic;

import com.vacantagent.accounting.config.AccountingProperties;
import com.vacantagent.accounting.data.PayoutAccountRepository;
import com.vacantagent.accounting.data.PayoutRepository;
import com.vacantagent.accounting.entities.AccountType;
import com.vacantagent.accounting.entities.EntryType;
import com.vacantagent.accounting.entities.Payout;
import com.vacantagent.accounting.entities.PayoutAccount;
import com.vacantagent.accounting.entities.PayoutStatus;
import com.vacantagent.common.money.Money;
import com.vacantagent.common.money.OriginalAmount;
import com.vacantagent.paypal.PayPalNotConfiguredException;
import com.vacantagent.paypal.PayPalPayouts;
import com.vacantagent.paypal.PayPalRejectedException;
import com.vacantagent.paypal.PayPalUnavailableException;
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
 * Providers withdraw their {@link AccountType#EARNINGS} to the linked PayPal account. The amount is debited before
 * PayPal is called, so it cannot be paid out twice; if PayPal refuses or returns the payout, it is booked back,
 * see Docs/accounting.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutLogic {
    private final PayoutRepository payoutRepository;
    private final PayoutAccountRepository payoutAccountRepository;
    private final AccountLedger ledger;
    private final PayPalPayouts payPalPayouts;
    private final AccountingProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public Money available(long appUserId) {
        return ledger.balance(appUserId, AccountType.EARNINGS);
    }

    /**
     * @param amount  debited amount in the base currency
     * @param paidOut amount the provider receives at PayPal, with the exchange rate used for {@code amount}
     * @throws PayoutAccountRequiredException if no PayPal account is linked
     * @throws InsufficientBalanceException   if the earnings do not cover the amount
     */
    public Payout request(long appUserId, Money amount, OriginalAmount paidOut) {
        PayoutAccount payoutAccount = payoutAccountRepository.findByAppUserId(appUserId)
                .orElseThrow(PayoutAccountRequiredException::new);
        if (amount.isLessThan(properties.minPayout())) {
            throw new AmountOutOfRangeException("Payout must be at least " + properties.minPayout());
        }
        Payout payout = transactionTemplate.execute(_ -> {
            Payout requested = payoutRepository.save(new Payout(appUserId, amount, paidOut,
                    payoutAccount.getExternalAccountId(), now()));
            ledger.debit(appUserId, AccountType.EARNINGS, amount, EntryType.PAYOUT, requested.getId());
            return requested;
        });
        return submit(payout);
    }

    public List<Payout> latest(long appUserId, int limit) {
        return payoutRepository.findByAppUserIdOrderByRequestedAtDescIdDesc(appUserId, PageRequest.of(0, limit));
    }

    /** Reads the state of submitted payouts from PayPal, called by {@code PaymentStatusPoller}. */
    public void checkSubmitted() {
        for (Payout payout : payoutRepository.findByStatus(PayoutStatus.SUBMITTED)) {
            try {
                apply(payout.getId(), payPalPayouts.getPayout(payout.getPaypalBatchId()));
            } catch (RuntimeException e) {
                log.warn("Checking payout {} failed", payout.getId(), e);
            }
        }
    }

    private Payout submit(Payout payout) {
        try {
            PayPalPayouts.PayoutBatch batch = payPalPayouts.send(payout.senderBatchId(), payout.getReceiver(),
                    PayPalAmounts.of(payout.getPaidOut()), "Your VacantAgent payout",
                    "Earnings from VacantAgent, payout " + payout.getId());
            return update(payout.getId(), p -> p.submitted(batch.payoutBatchId(), batch.transactionStatus()));
        } catch (PayPalRejectedException e) {
            return failAndReverse(payout.getId(), "PayPal refused the payout: " + e.getIssue());
        } catch (PayPalNotConfiguredException e) {
            return failAndReverse(payout.getId(), "PayPal is not configured");
        } catch (PayPalUnavailableException e) {
            log.error("Payout {} of user {}: outcome unknown, check it in the PayPal dashboard (sender batch id {})",
                    payout.getId(), payout.getAppUserId(), payout.senderBatchId(), e);
            return update(payout.getId(), p -> p.unknown(e.getMessage()));
        }
    }

    void apply(long payoutId, PayPalPayouts.PayoutBatch batch) {
        if (batch.isReturned()) {
            failAndReverse(payoutId, "PayPal status " + batch.transactionStatus()
                    + (batch.error() == null ? "" : " (" + batch.error() + ")"));
        } else {
            update(payoutId, payout -> {
                if (payout.getStatus() != PayoutStatus.SUBMITTED) {
                    return;
                }
                if (batch.isSucceeded()) {
                    payout.complete(batch.transactionStatus(), now());
                } else {
                    payout.stillOpen(batch.transactionStatus());
                }
            });
        }
    }

    /** Books the amount back onto the earnings account, exactly once. */
    private Payout failAndReverse(long payoutId, String reason) {
        return update(payoutId, payout -> {
            if (payout.getStatus() == PayoutStatus.COMPLETED || payout.getStatus() == PayoutStatus.FAILED) {
                return;
            }
            payout.fail(reason, now());
            ledger.credit(payout.getAppUserId(), AccountType.EARNINGS, payout.getAmount(), EntryType.PAYOUT_REVERSAL, payoutId);
            log.warn("Payout {} of user {} failed, {} booked back: {}", payoutId, payout.getAppUserId(), payout.getAmount(), reason);
        });
    }

    private Payout update(long payoutId, Consumer<Payout> change) {
        return transactionTemplate.execute(_ -> {
            Payout payout = payoutRepository.findByIdForUpdate(payoutId).orElseThrow();
            change.accept(payout);
            return payout;
        });
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
