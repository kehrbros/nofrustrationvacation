package com.vacantagent.market.mapper;

import com.vacantagent.common.money.Money;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.ConsumerOrder;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderMapper {
    private final CurrencyConversionService currencyConversionService;

    /** Limits are converted to the base currency. */
    public ConsumerOrder toEntity(OrderDto dto) {
        return new ConsumerOrder(toMoney(dto.maxCostPerMillionInputTokens()), toMoney(dto.maxCostPerMillionOutputTokens()),
                dto.marketWaitTime());
    }

    /** Missing limits stay {@code null}, so the market rejects the order instead of failing here. */
    private @Nullable Money toMoney(@Nullable MoneyDto dto) {
        return (dto == null) ? null : currencyConversionService.fromDto(dto);
    }
}