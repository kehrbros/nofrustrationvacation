package com.vacantagent.market.mapper;

import com.vacantagent.agent.entities.Agent;
import com.vacantagent.agent.logic.AgentLogic;
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
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.vacantagent.market.MarketFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapperTest {

    private final CurrencyConversionService currencyConversionService = new CurrencyConversionService(
            mock(ExchangeRateRepository.class), Clock.systemUTC(), List.of(), 4);
    private final AgentLogic agentLogic = mock(AgentLogic.class);

    @Test
    void mapsOrderDtoToEntity() {
        ConsumerOrder order = orderMapper().toEntity(
                new OrderDto(usd("1.5"), usd("2.5"), Duration.ofMinutes(3), "provider", "model"), 7L);

        assertThat(order.getId()).isZero();
        assertThat(order.getMaxCostPerMillionInputTokens()).isEqualTo(Money.of("1.5"));
        assertThat(order.getMaxCostPerMillionOutputTokens()).isEqualTo(Money.of("2.5"));
        assertThat(order.getMarketWaitTime()).isEqualTo(Duration.ofMinutes(3));
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getModelProvider()).isEqualTo("provider");
        assertThat(order.getModelName()).isEqualTo("model");
        assertThat(order.getAppUserId()).isEqualTo(7L);
    }

    @Test
    void mapsMissingOrderLimitsToNull() {
        ConsumerOrder order = orderMapper().toEntity(new OrderDto(null, null, null, null, null), 7L);

        assertThat(order.getMaxCostPerMillionInputTokens()).isNull();
        assertThat(order.getMaxCostPerMillionOutputTokens()).isNull();
    }

    @Test
    void mapsOfferDtoToEntityWithAllTokensAvailableAndTheModelOfItsAgent() {
        Agent agent = withId(new Agent(7L, "model", "provider", null, OffsetDateTime.now()), 42);
        when(agentLogic.findOwned(7L, 42L)).thenReturn(Optional.of(agent));
        OffsetDateTime until = OffsetDateTime.now().plusDays(1);

        ProviderOffer offer = offerMapper().toEntity(new OfferDto(usd("1.5"), usd("2.5"), until, 42L, 1000L), 7L);

        assertThat(offer.getId()).isZero();
        assertThat(offer.getPricePerMillionInputTokens()).isEqualTo(Money.of("1.5"));
        assertThat(offer.getPricePerMillionOutputTokens()).isEqualTo(Money.of("2.5"));
        assertThat(offer.getOfferedUntil()).isEqualTo(until);
        assertThat(offer.getAgentId()).isEqualTo(42L);
        assertThat(offer.getModelProvider()).isEqualTo("provider");
        assertThat(offer.getModelName()).isEqualTo("model");
        assertThat(offer.getTotalTokens()).isEqualTo(1000L);
        assertThat(offer.getAvailableTokens()).isEqualTo(1000L);
        assertThat(offer.getLastErrorAt()).isNull();
        assertThat(offer.getAppUserId()).isEqualTo(7L);
    }

    @Test
    void offerWithAgentOfAnotherUserFails() {
        when(agentLogic.findOwned(7L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> offerMapper().toEntity(new OfferDto(usd("1.5"), usd("2.5"), null, 42L, 1000L), 7L))
                .isInstanceOf(NoSuchElementException.class);
    }

    private OfferMapper offerMapper() {
        return new OfferMapper(currencyConversionService, agentLogic);
    }

    private OrderMapper orderMapper() {
        return new OrderMapper(currencyConversionService, Clock.systemUTC());
    }

    private static MoneyDto usd(String amount) {
        return new MoneyDto(new BigDecimal(amount), "USD");
    }
}