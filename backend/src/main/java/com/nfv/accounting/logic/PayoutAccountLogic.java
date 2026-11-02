package com.nfv.accounting.logic;

import com.nfv.accounting.config.AccountingProperties;
import com.nfv.accounting.data.PayoutAccountLinkStateRepository;
import com.nfv.accounting.data.PayoutAccountRepository;
import com.nfv.accounting.data.PayoutRepository;
import com.nfv.accounting.entities.Payout;
import com.nfv.accounting.entities.PayoutAccount;
import com.nfv.accounting.entities.PayoutAccountLinkState;
import com.nfv.paypal.PayPalAccount;
import com.nfv.paypal.PayPalConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Links the PayPal account a provider gets paid out to. Linking uses "Log in with PayPal", so the account is
 * confirmed by its owner, see Docs/accounting.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutAccountLogic {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PayoutAccountRepository payoutAccountRepository;
    private final PayoutAccountLinkStateRepository linkStateRepository;
    private final PayoutRepository payoutRepository;
    private final PayPalConnect payPalConnect;
    private final AccountingProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public Optional<PayoutAccount> find(long appUserId) {
        return payoutAccountRepository.findByAppUserId(appUserId);
    }

    /** Step of the provider onboarding. */
    public boolean hasPayoutAccount(long appUserId) {
        return payoutAccountRepository.existsByAppUserId(appUserId);
    }

    /**
     * Starts linking: remembers a random state for the user and returns the PayPal login URL to send the user to.
     * Earlier unfinished links of the user become invalid.
     */
    public URI startLink(long appUserId) {
        String state = randomState();
        OffsetDateTime now = OffsetDateTime.now(clock);
        transactionTemplate.executeWithoutResult(_ -> {
            linkStateRepository.deleteByAppUserIdOrExpired(appUserId, now.minus(properties.linkStateTtl()));
            linkStateRepository.save(new PayoutAccountLinkState(state, appUserId, now));
        });
        return payPalConnect.authorizationUrl(state);
    }

    /**
     * Completes linking with the values PayPal passed to the redirect URI. Replaces an account linked before.
     *
     * @throws PayoutAccountLinkException if the state is unknown, expired or of another user, or the PayPal account
     *                                    is already linked to another user
     */
    public PayoutAccount completeLink(long appUserId, String authorizationCode, String state) {
        consumeState(appUserId, state);
        PayPalAccount payPalAccount = payPalConnect.fetchAccount(authorizationCode);
        return transactionTemplate.execute(_ -> {
            if (payoutAccountRepository.existsByProviderAndExternalAccountIdAndAppUserIdNot(PayoutAccount.PAYPAL,
                    payPalAccount.payerId(), appUserId)) {
                throw new PayoutAccountLinkException(PayoutAccountLinkException.ACCOUNT_IN_USE,
                        "PayPal account is already linked to another user");
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            PayoutAccount account = payoutAccountRepository.findByAppUserId(appUserId)
                    .map(existing -> {
                        existing.relink(payPalAccount.payerId(), payPalAccount.email(), payPalAccount.verified(), now);
                        return existing;
                    })
                    .orElseGet(() -> payoutAccountRepository.save(new PayoutAccount(appUserId, PayoutAccount.PAYPAL,
                            payPalAccount.payerId(), payPalAccount.email(), payPalAccount.verified(), now)));
            log.info("User {} linked PayPal account {} (verified: {})", appUserId, payPalAccount.payerId(), payPalAccount.verified());
            return account;
        });
    }

    /**
     * @throws PayoutAccountLinkException while payouts are open, their outcome must be known first
     */
    public void unlink(long appUserId) {
        transactionTemplate.executeWithoutResult(_ -> {
            if (payoutRepository.existsByAppUserIdAndStatusIn(appUserId, Payout.OPEN)) {
                throw new PayoutAccountLinkException(PayoutAccountLinkException.PAYOUTS_OPEN,
                        "Payouts are still in progress");
            }
            payoutAccountRepository.findByAppUserId(appUserId).ifPresent(payoutAccountRepository::delete);
        });
    }

    /** Deletes the state in any case, so it can be used only once. */
    private void consumeState(long appUserId, String state) {
        PayoutAccountLinkState linkState = transactionTemplate.execute(_ -> {
            Optional<PayoutAccountLinkState> found = linkStateRepository.findByState(state);
            found.ifPresent(linkStateRepository::delete);
            return found.orElse(null);
        });
        if (linkState == null) {
            throw invalidState("unknown");
        }
        if (linkState.getAppUserId() != appUserId) {
            throw invalidState("started by another user");
        }
        if (linkState.getCreatedAt().plus(properties.linkStateTtl()).isBefore(OffsetDateTime.now(clock))) {
            throw invalidState("expired");
        }
    }

    private static PayoutAccountLinkException invalidState(String reason) {
        return new PayoutAccountLinkException(PayoutAccountLinkException.INVALID_STATE, "Link state " + reason);
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
