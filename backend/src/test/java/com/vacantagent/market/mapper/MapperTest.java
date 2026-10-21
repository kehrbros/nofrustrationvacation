package com.vacantagent.market.mapper;

import com.vacantagent.common.money.Money;
import com.vacantagent.currency.data.ExchangeRateRepository;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MapperTest {

    private final CurrencyConversionService currencyConversionService = new CurrencyConversionService(
            mock(ExchangeRateRepository.class), Clock.systemUTC(), List.of(), 4);

    @Test
    void mapsOrderDtoToEntity() {
        ConsumerOrder order = new OrderMapper(currencyConversionService).toEntity(
                new OrderDto(usd("1.5"), usd("2.5"), Duration.ofMinutes(3)));

        assertThat(order.getId()).isZero();
        assertThat(order.getMaxCostPerMillionInputTokens()).isEqualTo(Money.of("1.5"));
        assertThat(order.getMaxCostPerMillionOutputTokens()).isEqualTo(Money.of("2.5"));
        assertThat(order.getMarketWaitTime()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void mapsMissingOrderLimitsToNull() {
        ConsumerOrder order = new OrderMapper(currencyConversionService).toEntity(new OrderDto(null, null, null));

        assertThat(order.getMaxCostPerMillionInputTokens()).isNull();
        assertThat(order.getMaxCostPerMillionOutputTokens()).isNull();
    }

    @Test
    void mapsOfferDtoToEntityWithAllTokensAvailable() {
        OffsetDateTime until = OffsetDateTime.now().plusDays(1);
        ProviderOffer offer = new OfferMapper(currencyConversionService).toEntity(new OfferDto(usd("1.5"), usd("2.5"),
                until, "provider", "model", 1000L));

        assertThat(offer.getId()).isZero();
        assertThat(offer.getPricePerMillionInputTokens()).isEqualTo(Money.of("1.5"));
        assertThat(offer.getPricePerMillionOutputTokens()).isEqualTo(Money.of("2.5"));
        assertThat(offer.getOfferedUntil()).isEqualTo(until);
        assertThat(offer.getModelProvider()).isEqualTo("provider");
        assertThat(offer.getModelName()).isEqualTo("model");
        assertThat(offer.getTotalTokens()).isEqualTo(1000L);
        assertThat(offer.getAvailableTokens()).isEqualTo(1000L);
        assertThat(offer.getLastErrorAt()).isNull();
    }

    private static MoneyDto usd(String amount) {
        return new MoneyDto(new BigDecimal(amount), "USD");
    }
}