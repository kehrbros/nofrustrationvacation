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

    /** An offer always sells one specific model. */
    public OfferDto prepare(OfferDto offer) {
        String modelProvider = trimToNull(offer.modelProvider());
        String modelName = trimToNull(offer.modelName());
        if (modelProvider == null || modelName == null) {
            throw new InvalidMarketRequestException("Offer requires modelProvider and modelName");
        }
        return new OfferDto(offer.pricePerMillionInputTokens(), offer.pricePerMillionOutputTokens(),
                offer.offeredUntil(), modelProvider, modelName, offer.totalTokens());
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}