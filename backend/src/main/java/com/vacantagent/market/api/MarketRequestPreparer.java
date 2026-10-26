package com.vacantagent.market.api;

import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import jakarta.annotation.Nullable;
import org.springframework.stereotype.Component;

/**
 * Prepares incoming orders and offers for the market, which only distinguishes "given" from {@code null}:
 * model values are trimmed and empty ones become {@code null}, combinations the market cannot express are refused.
 */
@Component
public class MarketRequestPreparer {

    /** An order may leave the model, or provider and model, open; a model without provider is refused. */
    public OrderDto prepare(OrderDto order) {
        String modelProvider = trimToNull(order.modelProvider());
        String modelName = trimToNull(order.modelName());
        if (modelProvider == null && modelName != null) {
            throw new InvalidMarketRequestException("modelName requires modelProvider");
        }
        return new OrderDto(order.maxCostPerMillionInputTokens(), order.maxCostPerMillionOutputTokens(),
                order.marketWaitTime(), modelProvider, modelName);
    }

    /**
     * An offer names either an existing agent or one specific model with a secret to call it. Whether the agent
     * exists and belongs to the user is checked by the caller.
     */
    public OfferDto prepare(OfferDto offer) {
        String modelProvider = trimToNull(offer.modelProvider());
        String modelName = trimToNull(offer.modelName());
        if (offer.agentId() != null) {
            if (modelProvider != null || modelName != null || trimToNull(offer.secret()) != null) {
                throw new InvalidMarketRequestException("Give either agentId or modelProvider, modelName and secret");
            }
            return new OfferDto(offer.pricePerMillionInputTokens(), offer.pricePerMillionOutputTokens(),
                    offer.offeredUntil(), offer.agentId(), null, null, null, offer.totalTokens());
        }
        if (modelProvider == null || modelName == null) {
            throw new InvalidMarketRequestException("Offer requires modelProvider and modelName");
        }
        String secret = trimToNull(offer.secret());
        if (secret == null) {
            throw new InvalidMarketRequestException("Offer requires secret");
        }
        return new OfferDto(offer.pricePerMillionInputTokens(), offer.pricePerMillionOutputTokens(),
                offer.offeredUntil(), null, modelProvider, modelName, secret, offer.totalTokens());
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}