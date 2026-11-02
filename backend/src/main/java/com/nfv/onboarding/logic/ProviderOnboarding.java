package com.nfv.onboarding.logic;

import com.nfv.accounting.logic.PayoutAccountLogic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Steps a provider completes before selling capacity, in any order, legacy of Nfv, see Docs/architecture.md. Registration is done by
 * the identity provider, so every API user has it.
 */
@Service
@RequiredArgsConstructor
public class ProviderOnboarding {
    private final PayoutAccountLogic payoutAccountLogic;

    public record Status(boolean registered, boolean payoutAccountLinked) {
        public boolean complete() {
            return registered && payoutAccountLinked;
        }
    }

    public Status status(long appUserId) {
        return new Status(true, payoutAccountLogic.hasPayoutAccount(appUserId));
    }
}