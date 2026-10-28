package com.vacantagent.market.mapper;

import com.vacantagent.agent.entities.Agent;
import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.common.money.Money;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.entities.ProviderOffer;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OfferMapper {
    private final CurrencyConversionService currencyConversionService;
    private final AgentLogic agentLogic;

    /**
     * A new offer starts with all of its tokens available and sells the model of its agent. Prices are converted to
     * the base currency.
     *
     * @throws java.util.NoSuchElementException if the agent does not exist or belongs to someone else; callers
     *                                          check {@code IMarketLogic.isAgentOwnedBy} first
     */
    public ProviderOffer toEntity(OfferDto dto, long appUserId) {
        Agent agent = agentLogic.findOwned(appUserId, dto.agentId()).orElseThrow();
        return new ProviderOffer(toMoney(dto.pricePerMillionInputTokens()), toMoney(dto.pricePerMillionOutputTokens()),
                dto.offeredUntil(), agent.getId(), agent.getModelProvider(), agent.getName(), null, null,
                dto.totalTokens(), dto.totalTokens(), appUserId, null);
    }

    /** Missing prices stay {@code null}, so the market rejects the offer instead of failing here. */
    private @Nullable Money toMoney(@Nullable MoneyDto dto) {
        return (dto == null) ? null : currencyConversionService.fromDto(dto);
    }
}