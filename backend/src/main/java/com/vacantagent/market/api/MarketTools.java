package com.vacantagent.market.api;

import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.dto.SubmissionDto;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.logic.IMarketLogic;
import com.vacantagent.market.matching.MatchResult;
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
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The market's MCP tools for consumer and provider agents. Orders and offers belong to the authenticated user, only
 * the owner may cancel them. The sync MCP server runs tools on the request thread, so the JWT's user is available.
 */
@Component
@RequiredArgsConstructor
public class MarketTools {
    /** Upper bound for an order's wait, below common MCP client timeouts. */
    static final Duration MAX_WAIT = Duration.ofSeconds(60);
    /** Persisting an agreement or completing a command, beyond the order's own wait. */
    private static final Duration GRACE = Duration.ofSeconds(10);

    private final IMarketLogic market;
    private final MarketRequestPreparer preparer;
    private final SubmissionDtoMapper submissionMapper;

    @McpTool(name = "place_order", description = "Buy LLM access. Matched against the cheapest fitting offer for the "
            + "requested model. Waits until matched or marketWaitTime is over. A user may have only one waiting "
            + "order per modelProvider and modelName. Returns MATCHED with the agreement, REJECTED with a reason, "
            + "or CANCELLED.")
    public SubmissionDto placeOrder(
            @McpToolParam(description = "Highest accepted price per million input tokens, decimal string, e.g. \"1.50\"")
            String maxCostPerMillionInputTokens,
            @McpToolParam(description = "Highest accepted price per million output tokens, decimal string, e.g. \"12.00\"")
            String maxCostPerMillionOutputTokens,
            @McpToolParam(description = "ISO 4217 currency of both prices: USD, EUR, GBP or CHF")
            String currency,
            @McpToolParam(description = "How long to wait for a fitting offer, ISO 8601 duration, at most PT60S. "
                    + "PT0S only matches on arrival.")
            String marketWaitTime,
            @McpToolParam(description = "Only offers of this provider, e.g. \"openai\". Missing means any provider, "
                    + "then modelName must be missing too.", required = false)
            @Nullable String modelProvider,
            @McpToolParam(description = "Only offers of this model, e.g. \"gpt-5\". Missing means any model of "
                    + "modelProvider.", required = false)
            @Nullable String modelName) {
        CurrentUser user = currentUser();
        Duration waitTime = waitTime(marketWaitTime);
        OrderDto order = preparer.prepare(new OrderDto(money(maxCostPerMillionInputTokens, currency),
                money(maxCostPerMillionOutputTokens, currency), waitTime, modelProvider, modelName));
        Currency responseCurrency = submissionMapper.responseCurrency(user);
        Submission submission = market.acceptOrderAndAwaitMatch(order, user.appUserId());
        return submissionMapper.toDto(submission.id(), await(submission, waitTime.plus(GRACE)), responseCurrency);
    }

    @McpTool(name = "cancel_order", description = "Cancel a waiting order. Fails if it is not waiting: already "
            + "matched, cancelled, expired, unknown or placed by another user.")
    public String cancelOrder(@McpToolParam(description = "Order id from place_order") long orderId) {
        if (!market.isOrderOwnedBy(orderId, currentUser().appUserId())) throw notWaiting("Order", orderId);
        return cancelled(market.cancelOrder(orderId), "Order", orderId);
    }

    @McpTool(name = "place_offer", description = "Sell LLM access. Matched against the highest-paying fitting order "
            + "for its model. Served by an existing agent (agentId) or a new one from modelProvider, modelName and secret. "
            + "If none fits, the offer waits in the market until offeredUntil. Returns MATCHED with "
            + "the agreement, RESTING, or REJECTED with a reason.")
    public SubmissionDto placeOffer(
            @McpToolParam(description = "Price per million input tokens, decimal string, e.g. \"1.25\"")
            String pricePerMillionInputTokens,
            @McpToolParam(description = "Price per million output tokens, decimal string, e.g. \"10.00\"")
            String pricePerMillionOutputTokens,
            @McpToolParam(description = "ISO 4217 currency of both prices: USD, EUR, GBP or CHF")
            String currency,
            @McpToolParam(description = "Id of an existing agent of yours. Replaces modelProvider, modelName and "
                    + "secret, which must then be missing.", required = false)
            @Nullable Long agentId,
            @McpToolParam(description = "Provider of the offered model, e.g. \"openai\". Required without agentId.",
                    required = false)
            @Nullable String modelProvider,
            @McpToolParam(description = "Offered model, e.g. \"gpt-5\". Required without agentId.", required = false)
            @Nullable String modelName,
            @McpToolParam(description = "Credential for calling the offered agent. Required without agentId.",
                    required = false)
            @Nullable String secret,
            @McpToolParam(description = "End of the offer and of any agreement made from it, ISO 8601 date-time "
                    + "with offset. Missing means no end.", required = false)
            @Nullable String offeredUntil,
            @McpToolParam(description = "Tokens offered in total", required = false)
            @Nullable Long totalTokens) {
        CurrentUser user = currentUser();
        OfferDto offer = preparer.prepare(new OfferDto(money(pricePerMillionInputTokens, currency),
                money(pricePerMillionOutputTokens, currency), dateTime(offeredUntil), agentId, modelProvider,
                modelName, secret, totalTokens));
        if (agentId != null && !market.isAgentOwnedBy(agentId, user.appUserId())) {
            throw new InvalidMarketRequestException("Agent " + agentId + " not found");
        }
        Currency responseCurrency = submissionMapper.responseCurrency(user);
        Submission submission = market.acceptOffer(offer, user.appUserId());
        return submissionMapper.toDto(submission.id(), await(submission, GRACE), responseCurrency);
    }

    @McpTool(name = "cancel_offer", description = "Cancel a waiting offer. Fails if it is not waiting: already "
            + "matched, cancelled, expired, unknown or placed by another user.")
    public String cancelOffer(@McpToolParam(description = "Offer id from place_offer") long offerId) {
        if (!market.isOfferOwnedBy(offerId, currentUser().appUserId())) throw notWaiting("Offer", offerId);
        return cancelled(market.cancelOffer(offerId), "Offer", offerId);
    }

    // ------------------------------------------------------------------ helpers

    private static CurrentUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) {
            throw new IllegalStateException("No authenticated user");
        }
        return user;
    }

    private static Duration waitTime(String value) {
        Duration waitTime = parse(value, Duration::parse, "marketWaitTime must be an ISO 8601 duration");
        if (waitTime.isNegative() || waitTime.compareTo(MAX_WAIT) > 0) {
            throw new InvalidMarketRequestException("marketWaitTime must be between PT0S and " + MAX_WAIT);
        }
        return waitTime;
    }

    private static MoneyDto money(String amount, String currency) {
        return new MoneyDto(parse(amount, BigDecimal::new, "Prices must be decimal strings"), currency);
    }

    private static @Nullable OffsetDateTime dateTime(@Nullable String value) {
        return value == null || value.isBlank() ? null
                : parse(value, OffsetDateTime::parse, "offeredUntil must be an ISO 8601 date-time with offset");
    }

    private static <T> T parse(@Nullable String value, Function<String, T> parser, String message) {
        if (value == null) throw new InvalidMarketRequestException(message);
        try {
            return parser.apply(value.strip());
        } catch (RuntimeException e) {
            throw new InvalidMarketRequestException(message);
        }
    }

    private static MatchResult await(Submission submission, Duration timeout) {
        try {
            return submission.result().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the market", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Market did not answer for submission " + submission.id(), e);
        }
    }

    private static String cancelled(CompletableFuture<MatchResult> cancel, String kind, long id) {
        MatchResult result = cancel.join();
        if (!(result instanceof MatchResult.Cancelled)) throw notWaiting(kind, id);
        return kind + " " + id + " cancelled";
    }

    private static InvalidMarketRequestException notWaiting(String kind, long id) {
        return new InvalidMarketRequestException(kind + " " + id + " is not waiting");
    }
}