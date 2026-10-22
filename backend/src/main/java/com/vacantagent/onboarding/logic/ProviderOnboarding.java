package com.vacantagent.onboarding.logic;

import com.vacantagent.accounting.logic.PayoutAccountLogic;
import com.vacantagent.agent.logic.AgentLogic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Steps a provider completes before placing offers, in any order, see Docs/onboarding.md. Registration is done by
 * the identity provider, so every API user has it.
 */
@Service
@RequiredArgsConstructor
public class ProviderOnboarding {
    private final PayoutAccountLogic payoutAccountLogic;
    private final AgentLogic agentLogic;

    public record Status(boolean registered, boolean payoutAccountLinked, boolean agentConfigured) {
        public boolean complete() {
            return registered && payoutAccountLinked && agentConfigured;
        }
    }

    public Status status(long appUserId) {
        return new Status(true, payoutAccountLogic.hasPayoutAccount(appUserId), agentLogic.hasAgent(appUserId));
    }

    /** Offers require a completed onboarding: earnings need a payout account, sold tokens an agent. */
    public boolean mayPlaceOffers(long appUserId) {
        return status(appUserId).complete();
    }
}
