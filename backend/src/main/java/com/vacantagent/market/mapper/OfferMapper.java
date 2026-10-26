package com.vacantagent.market.mapper;

import com.vacantagent.common.money.Money;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.market.data.AgentRepository;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.entities.Agent;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.user.data.AppUserRepository;
import com.vacantagent.user.entities.AppUser;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OfferMapper {
    private final CurrencyConversionService currencyConversionService;
    private final AppUserRepository appUserRepository;
    private final AgentRepository agentRepository;

    /**
     * A new offer starts with all of its tokens available. Its agent is the existing one of {@code agentId}, else a
     * new one owned by the user. Prices are converted to the base currency. The owner is only referenced, not
     * loaded: an authenticated request always has an existing {@code AppUser}.
     */
    public ProviderOffer toEntity(OfferDto dto, long appUserId) {
        AppUser owner = appUserRepository.getReferenceById(appUserId);
        return new ProviderOffer(toMoney(dto.pricePerMillionInputTokens()), toMoney(dto.pricePerMillionOutputTokens()),
                dto.offeredUntil(), agent(dto, owner), null, null,
                dto.totalTokens(), dto.totalTokens(), owner);
    }

    /** An existing agent is loaded, not only referenced: the order book reads its model outside a transaction. */
    private Agent agent(OfferDto dto, AppUser owner) {
        if (dto.agentId() != null) return agentRepository.findById(dto.agentId()).orElseThrow();
        return new Agent(dto.modelProvider(), dto.modelName(), dto.secret(), owner);
    }

    /** Missing prices stay {@code null}, so the market rejects the offer instead of failing here. */
    private @Nullable Money toMoney(@Nullable MoneyDto dto) {
        return (dto == null) ? null : currencyConversionService.fromDto(dto);
    }
}