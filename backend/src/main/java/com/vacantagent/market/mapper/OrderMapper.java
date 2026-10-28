package com.vacantagent.market.mapper;

import com.vacantagent.common.money.Money;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.user.data.AppUserRepository;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class OrderMapper {
    private final CurrencyConversionService currencyConversionService;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    /**
     * Limits are converted to the base currency. The market wait time starts now. The owner is only referenced,
     * not loaded: an authenticated request always has an existing {@code AppUser}.
     */
    public ConsumerOrder toEntity(OrderDto dto, long appUserId) {
        return new ConsumerOrder(toMoney(dto.maxCostPerMillionInputTokens()), toMoney(dto.maxCostPerMillionOutputTokens()),
                dto.marketWaitTime(), OffsetDateTime.now(clock), dto.modelProvider(), dto.modelName(), null,
                appUserRepository.getReferenceById(appUserId), null);
    }

    /** Missing limits stay {@code null}, so the market rejects the order instead of failing here. */
    private @Nullable Money toMoney(@Nullable MoneyDto dto) {
        return (dto == null) ? null : currencyConversionService.fromDto(dto);
    }
}