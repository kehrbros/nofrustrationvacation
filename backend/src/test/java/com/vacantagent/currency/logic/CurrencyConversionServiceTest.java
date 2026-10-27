package com.vacantagent.currency.logic;

import com.vacantagent.common.money.CurrencyAmount;
import com.vacantagent.common.money.Money;
import com.vacantagent.currency.data.ExchangeRateRepository;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.entities.ExchangeRate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrencyConversionServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");

    private final ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
    private final CurrencyConversionService service = new CurrencyConversionService(repository,
            Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC), List.of("EUR", "JPY"), 4);

    @Test
    void convertsIncomingAmountToBase() {
        givenLatestRate("EUR", "0.8", TODAY);

        CurrencyConversion conversion = service.toBase(CurrencyAmount.of("10.00", "EUR"));

        assertEquals(Money.of("12.5"), conversion.base());
        assertEquals(new BigDecimal("0.8"), conversion.exchangeRate());
        assertEquals(TODAY, conversion.exchangeRateDate());
    }

    @Test
    void baseCurrencyIsNotConverted() {
        CurrencyConversion conversion = service.toBase(CurrencyAmount.of("10.123456789", "USD"));

        assertEquals(Money.of("10.12345679"), conversion.base());
        assertNull(conversion.exchangeRate());
    }

    @Test
    void roundsOutgoingAmountsPerUseCase() {
        givenLatestRate("EUR", "0.9", TODAY);
        givenLatestRate("JPY", "150.5", TODAY);
        Money money = Money.of("12.3456789");

        assertEquals(new BigDecimal("11.11"), service.toDto(money, EUR, MoneyRounding.AMOUNT).amount());
        assertEquals(new BigDecimal("11.11"), service.toDto(money, EUR, MoneyRounding.PAYOUT).amount());
        assertEquals(new BigDecimal("11.111111"), service.toDto(money, EUR, MoneyRounding.PRICE).amount());
        assertEquals(new BigDecimal("1858"), service.toDto(money, JPY, MoneyRounding.AMOUNT).amount());
        assertEquals(new BigDecimal("1858"), service.toDto(Money.of("12.3499"), JPY, MoneyRounding.PAYOUT).amount());
        assertEquals(new BigDecimal("1859"), service.toDto(Money.of("12.3499"), JPY, MoneyRounding.AMOUNT).amount());
    }

    @Test
    void outgoingDtoContainsRate() {
        givenLatestRate("EUR", "0.9", LocalDate.of(2026, 10, 20));

        MoneyDto dto = service.toDto(Money.of("10"), EUR, MoneyRounding.AMOUNT);

        assertEquals(new MoneyDto(new BigDecimal("9.00"), "EUR", new BigDecimal("0.9"), LocalDate.of(2026, 10, 20)), dto);
    }

    @Test
    void historicalValuesUseRateOfEventDate() {
        LocalDate eventDate = LocalDate.of(2026, 1, 15);
        when(repository.findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("EUR", eventDate))
                .thenReturn(Optional.of(rate("EUR", "0.5", eventDate)));

        assertEquals(new BigDecimal("5.00"), service.toDto(Money.of("10"), EUR, MoneyRounding.AMOUNT, eventDate).amount());
    }

    @Test
    void staleRateIsRejectedForBookingsOnly() {
        givenLatestRate("EUR", "0.8", TODAY.minusDays(5));

        assertEquals(Money.of("12.5"), service.toBase(CurrencyAmount.of("10", "EUR")).base());
        assertThrows(ExchangeRateUnavailableException.class, () -> service.toBaseForBooking(CurrencyAmount.of("10", "EUR")));
    }

    @Test
    void rateOfFourDaysIsAcceptedForBookings() {
        givenLatestRate("EUR", "0.8", TODAY.minusDays(4));

        assertEquals(Money.of("12.5"), service.toBaseForBooking(CurrencyAmount.of("10", "EUR")).base());
    }

    @Test
    void unsupportedOrUnknownCurrencyIsRejected() {
        assertThrows(UnsupportedCurrencyException.class, () -> service.toBase(CurrencyAmount.of("1", "GBP")));
        assertThrows(UnsupportedCurrencyException.class, () -> service.getSupportedCurrency("XYZ"));
        assertThrows(ExchangeRateUnavailableException.class, () -> service.toBase(CurrencyAmount.of("1", "JPY")));
    }

    @Test
    void responseCurrencyFallsBackToPreferenceAndBase() {
        assertEquals(EUR, service.getResponseCurrency("EUR", "JPY"));
        assertEquals(JPY, service.getResponseCurrency(null, "JPY"));
        assertEquals(Money.BASE_CURRENCY, service.getResponseCurrency(null, null));
        assertEquals(Money.BASE_CURRENCY, service.getResponseCurrency(" ", "GBP"), "no longer supported preference");
    }

    private void givenLatestRate(String currency, String rate, LocalDate rateDate) {
        when(repository.findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(eq(currency), any()))
                .thenReturn(Optional.of(rate(currency, rate, rateDate)));
    }

    private static ExchangeRate rate(String currency, String rate, LocalDate rateDate) {
        return new ExchangeRate(currency, new BigDecimal(rate), rateDate, OffsetDateTime.now(), "TEST");
    }
}
