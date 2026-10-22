package com.vacantagent.market.mapper;

import com.vacantagent.common.money.Money;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.user.data.AppUserRepository;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OfferMapper {
    private final CurrencyConversionService currencyConversionService;
    private final AppUserRepository appUserRepository;

    /**
     * A new offer starts with all of its tokens available. Prices are converted to the base currency. The owner is
     * only referenced, not loaded: an authenticated request always has an existing {@code AppUser}.
     */
    public ProviderOffer toEntity(OfferDto dto, long appUserId) {
        return new ProviderOffer(toMoney(dto.pricePerMillionInputTokens()), toMoney(dto.pricePerMillionOutputTokens()),
                dto.offeredUntil(), dto.modelProvider(), dto.modelName(), null, null,
                dto.totalTokens(), dto.totalTokens(), appUserRepository.getReferenceById(appUserId));
    }

    /** Missing prices stay {@code null}, so the market rejects the offer instead of failing here. */
    private @Nullable Money toMoney(@Nullable MoneyDto dto) {
        return (dto == null) ? null : currencyConversionService.fromDto(dto);
    }
}