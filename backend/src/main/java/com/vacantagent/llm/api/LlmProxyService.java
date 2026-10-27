package com.vacantagent.llm.api;

import com.vacantagent.accounting.logic.UsageBilling;
import com.vacantagent.agent.entities.Agent;
import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.common.money.Money;
import com.vacantagent.llm.data.LlmProtocolRepository;
import com.vacantagent.llm.dto.LlmCompletion;
import com.vacantagent.llm.dto.OrderLimits;
import com.vacantagent.llm.entities.LlmProtocol;
import com.vacantagent.llm.logic.ChatModelFactory;
import com.vacantagent.llm.logic.LlmCallFailedException;
import com.vacantagent.llm.logic.LlmProxyLogic;
import com.vacantagent.llm.logic.LlmRequestException;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.logic.IMarketLogic;
import com.vacantagent.market.matching.MatchResult;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends a consumer's prompt through one of their agreements to the provider's agent and settles the usage, see
 * Docs/llm.md. Runs the flow only; the rules are checks of {@link LlmProxyLogic} and the entities. The call to the agent
 * runs outside any transaction, so no row stays locked while the LLM answers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProxyService {
    /** Persisting the agreement, beyond the order's own wait. */
    private static final Duration GRACE = Duration.ofSeconds(10);

    private final LlmProxyLogic llmProxyLogic;
    private final IMarketLogic market;
    private final AgentLogic agentLogic;
    private final UsageBilling usageBilling;
    private final ChatModelFactory chatModelFactory;
    private final LlmProtocolRepository protocols;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * Requires a positive credit balance and an active agreement of the consumer for the model, which is ordered
     * first if missing and {@code orderLimits} are given. On success the tokens
     * are taken from the offer, the cost moves from the consumer's credit (which may go below zero) to the provider's
     * earnings, and the offer re-enters the market if it has tokens left. On failure nothing is charged and the offer
     * records the error. Both are protocolled.
     *
     * @param modelProvider only agreements for this provider, {@code null} or blank for any
     * @param modelName     only agreements for this model, {@code null} or blank for any
     * @param orderLimits   without a fitting agreement, an order for the model is placed with these limits and the
     *                      prompt waits for its agreement; {@code null} refuses the prompt instead
     * @throws LlmRequestException    if the prompt is missing, the credit is not positive or no agreement fits
     * @throws LlmCallFailedException if the agent did not complete the prompt
     */
    public LlmCompletion prompt(long appUserId, String prompt, @Nullable String modelProvider,
                                @Nullable String modelName, @Nullable OrderLimits orderLimits) {
        if (!llmProxyLogic.isPromptValid(prompt)) throw new LlmRequestException("prompt is required");
        if (!llmProxyLogic.hasPositiveCredit(appUserId)) throw new LlmRequestException("Credit balance must be positive");
        String provider = blankToNull(modelProvider);
        String providerKey = provider == null ? null : provider.toLowerCase(Locale.ROOT);
        String model = blankToNull(modelName);
        Agreement agreement = market.findActiveAgreement(appUserId, providerKey, model)
                .or(() -> orderAgreement(appUserId, providerKey, model, orderLimits))
                .orElseThrow(() -> new LlmRequestException("No active agreement for the requested model"));
        ProviderOffer offer = agreement.getProviderOffer();
        long providerAppUserId = offer.getAppUser().getId();
        Agent agent = agentLogic.findOwned(providerAppUserId, offer.getAgentId()).orElseThrow(() ->
                new IllegalStateException("Agent " + offer.getAgentId() + " of offer " + offer.getId() + " not found"));

        OffsetDateTime startedAt = now();
        ChatResponse response;
        try {
            response = chatModelFactory.create(agent, agentLogic.apiKey(agent.getId())).call(new Prompt(prompt));
        } catch (RuntimeException e) {
            OffsetDateTime endedAt = now();
            transactionTemplate.executeWithoutResult(status -> {
                protocols.save(LlmProtocol.failed(agent.getId(), agreement.getId(), startedAt, endedAt));
                market.recordOfferError(offer.getId(), endedAt);
            });
            log.warn("Agent {} failed on agreement {}", agent.getId(), agreement.getId(), e);
            throw new LlmCallFailedException(agent.getId(), e);
        }
        OffsetDateTime endedAt = now();

        Usage usage = response.getMetadata().getUsage();
        long promptTokens = usage == null ? 0 : tokens(usage.getPromptTokens());
        long completionTokens = usage == null ? 0 : tokens(usage.getCompletionTokens());
        long totalTokens = usage == null || usage.getTotalTokens() == null
                ? promptTokens + completionTokens : usage.getTotalTokens();
        Money cost = agreement.costFor(promptTokens, completionTokens);

        ProviderOffer used = transactionTemplate.execute(status -> {
            protocols.save(LlmProtocol.succeeded(agent.getId(), agreement.getId(), startedAt, endedAt,
                    promptTokens, completionTokens, totalTokens));
            ProviderOffer updated = market.recordOfferSuccess(offer.getId(), totalTokens, endedAt);
            if (!market.recordAgreementSuccess(agreement.getId())) {
                log.info("Agreement {} became idle during the call, billed anyway", agreement.getId());
            }
            usageBilling.settle(agreement.getId(), appUserId, providerAppUserId, cost);
            return updated;
        });
        if (used.hasTokensLeft()) {
            market.resubmitOffer(used).whenComplete((result, e) -> {
                if (e == null) log.debug("Offer {} back in the market: {}", used.getId(), result);
                else log.warn("Resubmitting offer {} failed", used.getId(), e);
            });
        }
        return new LlmCompletion(text(response), agreement.getId(), promptTokens, completionTokens, totalTokens, cost);
    }

    /** Places an order with the limits and waits for its agreement; empty without limits. */
    private Optional<Agreement> orderAgreement(long appUserId, @Nullable String provider, @Nullable String model,
                                               @Nullable OrderLimits limits) {
        if (limits == null) return Optional.empty();
        if (provider == null && model != null) throw new LlmRequestException("modelName requires modelProvider");
        Submission submission = market.acceptOrderAndAwaitMatch(new OrderDto(limits.maxCostPerMillionInputTokens(),
                limits.maxCostPerMillionOutputTokens(), limits.marketWaitTime(), provider, model), appUserId);
        MatchResult result = await(submission, limits.marketWaitTime().plus(GRACE));
        if (!(result instanceof MatchResult.Matched)) {
            throw new LlmRequestException("No agreement for the requested model: "
                    + (result instanceof MatchResult.Rejected rejected ? rejected.reason() : "no fitting offer"));
        }
        return market.findActiveAgreement(appUserId, provider, model);    // loads the offer and its provider
    }

    private static MatchResult await(Submission submission, Duration timeout) {
        try {
            return submission.result().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the market", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Market did not answer for order " + submission.id(), e);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static long tokens(@Nullable Integer reported) {
        return reported == null ? 0 : reported;
    }

    private static String text(ChatResponse response) {
        Generation result = response.getResult();
        String text = result == null ? null : result.getOutput().getText();
        return text == null ? "" : text;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}