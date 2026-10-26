package com.vacantagent.market.mapper;

import com.vacantagent.common.money.Money;
import com.vacantagent.currency.data.ExchangeRateRepository;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.market.data.AgentRepository;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.Agent;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.user.data.AppUserRepository;
import com.vacantagent.user.entities.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapperTest {

    private final CurrencyConversionService currencyConversionService = new CurrencyConversionService(
            mock(ExchangeRateRepository.class), Clock.systemUTC(), List.of(), 4);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AppUser owner = AppUser.firstLogin("owner", null, null);

    @BeforeEach
    void setUp() {
        when(appUserRepository.getReferenceById(7L)).thenReturn(owner);
    }

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
        assertThat(order.getAppUser()).isSameAs(owner);
    }

    @Test
    void mapsMissingOrderLimitsToNull() {
        ConsumerOrder order = orderMapper().toEntity(new OrderDto(null, null, null, null, null), 7L);

        assertThat(order.getMaxCostPerMillionInputTokens()).isNull();
        assertThat(order.getMaxCostPerMillionOutputTokens()).isNull();
    }

    @Test
    void mapsOfferDtoToEntityWithAllTokensAvailableAndANewAgent() {
        OffsetDateTime until = OffsetDateTime.now().plusDays(1);
        ProviderOffer offer = offerMapper().toEntity(new OfferDto(usd("1.5"), usd("2.5"),
                until, null, "provider", "model", "secret", 1000L), 7L);

        assertThat(offer.getId()).isZero();
        assertThat(offer.getPricePerMillionInputTokens()).isEqualTo(Money.of("1.5"));
        assertThat(offer.getPricePerMillionOutputTokens()).isEqualTo(Money.of("2.5"));
        assertThat(offer.getOfferedUntil()).isEqualTo(until);
        assertThat(offer.getAgent().getModelProvider()).isEqualTo("provider");
        assertThat(offer.getAgent().getModelName()).isEqualTo("model");
        assertThat(offer.getAgent().getSecret()).isEqualTo("secret");
        assertThat(offer.getAgent().getAppUser()).isSameAs(owner);
        assertThat(offer.getTotalTokens()).isEqualTo(1000L);
        assertThat(offer.getAvailableTokens()).isEqualTo(1000L);
        assertThat(offer.getLastErrorAt()).isNull();
        assertThat(offer.getAppUser()).isSameAs(owner);
    }

    @Test
    void mapsOfferDtoWithAgentIdToTheLoadedAgent() {
        Agent agent = new Agent("provider", "model", "secret", owner);
        when(agentRepository.findById(42L)).thenReturn(Optional.of(agent));

        ProviderOffer offer = offerMapper().toEntity(new OfferDto(usd("1.5"), usd("2.5"),
                null, 42L, null, null, null, 1000L), 7L);

        assertThat(offer.getAgent()).isSameAs(agent);
    }

    private OfferMapper offerMapper() {
        return new OfferMapper(currencyConversionService, appUserRepository, agentRepository);
    }

    private OrderMapper orderMapper() {
        return new OrderMapper(currencyConversionService, appUserRepository, Clock.systemUTC());
    }

    private static MoneyDto usd(String amount) {
        return new MoneyDto(new BigDecimal(amount), "USD");
    }
}