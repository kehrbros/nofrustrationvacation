package com.vacantagent.llm.api;

import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.currency.logic.MoneyRounding;
import com.vacantagent.llm.dto.LlmCompletion;
import com.vacantagent.llm.dto.OrderLimits;
import com.vacantagent.llm.logic.LlmRequestException;
import com.vacantagent.user.logic.AppUserSettings;
import com.vacantagent.user.security.CurrentUser;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Currency;

/**
 * MCP tool of the LLM proxy for consumer agents, see Docs/llm.md. Prompts are sent through an agreement of the
 * authenticated user and billed to them.
 */
@Component
@RequiredArgsConstructor
public class LlmProxyTools {
    private final LlmProxyService proxyService;
    private final AppUserSettings appUserSettings;
    private final CurrencyConversionService currencyConversionService;

    /** Upper bound for the order's wait, below common MCP client timeouts. */
    static final Duration MAX_WAIT = Duration.ofSeconds(60);
    static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);

    @McpTool(name = "prompt", description = "Send a prompt to the LLM of one of your active agreements (see "
            + "place_order). Without a fitting agreement, places an order with the given price limits and waits for "
            + "its agreement. Requires a positive credit balance. The tokens used are charged at the agreed prices; "
            + "the credit may go below zero by this call. Fails if no agreement fits or the provider's LLM fails, "
            + "nothing is charged then.")
    public PromptResultDto prompt(
            @McpToolParam(description = "The prompt text") String prompt,
            @McpToolParam(description = "Only an agreement for this provider, e.g. \"openai\". Missing means any.",
                    required = false)
            @Nullable String modelProvider,
            @McpToolParam(description = "Only an agreement for this model, e.g. \"gpt-5\". Missing means any.",
                    required = false)
            @Nullable String modelName,
            @McpToolParam(description = "Without a fitting agreement: highest accepted price per million input "
                    + "tokens of the order placed, decimal string, e.g. \"1.50\". Missing means no order.",
                    required = false)
            @Nullable String maxCostPerMillionInputTokens,
            @McpToolParam(description = "Without a fitting agreement: highest accepted price per million output "
                    + "tokens of the order placed, decimal string, e.g. \"12.00\". Missing means no order.",
                    required = false)
            @Nullable String maxCostPerMillionOutputTokens,
            @McpToolParam(description = "ISO 4217 currency of both max costs: USD, EUR, GBP or CHF. Required with "
                    + "them.", required = false)
            @Nullable String currency,
            @McpToolParam(description = "How long the order waits for a fitting offer, ISO 8601 duration, at most "
                    + "PT60S. Missing means PT30S.", required = false)
            @Nullable String marketWaitTime) {
        CurrentUser user = currentUser();
        OrderLimits orderLimits = orderLimits(maxCostPerMillionInputTokens, maxCostPerMillionOutputTokens, currency,
                marketWaitTime);
        Currency responseCurrency = currencyConversionService.responseCurrency(null,
                appUserSettings.get(user.appUserId()).getPreferredCurrency());
        LlmCompletion completion = proxyService.prompt(user.appUserId(), prompt, modelProvider, modelName, orderLimits);
        return new PromptResultDto(completion.text(), completion.agreementId(), completion.promptTokens(),
                completion.completionTokens(), completion.totalTokens(),
                currencyConversionService.toDto(completion.cost(), responseCurrency, MoneyRounding.PRICE));
    }

    // ------------------------------------------------------------------ helpers

    /** {@code null} if no max cost is given; both max costs and the currency are required together. */
    private static @Nullable OrderLimits orderLimits(@Nullable String maxInput, @Nullable String maxOutput,
                                                     @Nullable String currency, @Nullable String marketWaitTime) {
        if (isBlank(maxInput) && isBlank(maxOutput)) return null;
        if (isBlank(maxInput) || isBlank(maxOutput) || isBlank(currency)) {
            throw new LlmRequestException("maxCostPerMillionInputTokens, maxCostPerMillionOutputTokens and currency "
                    + "are required together");
        }
        return new OrderLimits(money(maxInput, currency), money(maxOutput, currency), waitTime(marketWaitTime));
    }

    private static MoneyDto money(String amount, String currency) {
        try {
            return new MoneyDto(new BigDecimal(amount.strip()), currency.strip());
        } catch (NumberFormatException e) {
            throw new LlmRequestException("Max costs must be decimal strings");
        }
    }

    private static Duration waitTime(@Nullable String value) {
        if (isBlank(value)) return DEFAULT_WAIT;
        Duration waitTime;
        try {
            waitTime = Duration.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new LlmRequestException("marketWaitTime must be an ISO 8601 duration");
        }
        if (waitTime.isNegative() || waitTime.compareTo(MAX_WAIT) > 0) {
            throw new LlmRequestException("marketWaitTime must be between PT0S and " + MAX_WAIT);
        }
        return waitTime;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static CurrentUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) {
            throw new IllegalStateException("No authenticated user");
        }
        return user;
    }
}