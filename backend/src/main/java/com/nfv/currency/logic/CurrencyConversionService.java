package com.nfv.currency.logic;

import com.nfv.common.money.CurrencyAmount;
import com.nfv.common.money.Money;
import com.nfv.currency.data.ExchangeRateRepository;
import com.nfv.currency.dto.MoneyDto;
import com.nfv.currency.entities.ExchangeRate;
import jakarta.annotation.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Converts between the base currency and the currencies of API clients. Only to be used at the API boundary
 * (controllers, DTO mapping), logic works with {@link Money} only, see Docs/money.md.
 */
@Service
public class CurrencyConversionService {
    private final ExchangeRateRepository exchangeRateRepository;
    private final Clock clock;
    private final Set<String> supportedCurrencies;
    private final long maxRateAgeDays;
    private final Map<String, ExchangeRate> latestRates = new ConcurrentHashMap<>();

    public CurrencyConversionService(ExchangeRateRepository exchangeRateRepository, Clock clock,
                                     @Value("${nfv.money.currencies}") List<String> supportedCurrencies,
                                     @Value("${nfv.money.exchange-rates.max-age-days}") long maxRateAgeDays) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.clock = clock;
        this.supportedCurrencies = Stream.concat(supportedCurrencies.stream().map(String::trim), Stream.of(baseCode()))
                .collect(Collectors.toUnmodifiableSet());
        this.maxRateAgeDays = maxRateAgeDays;
    }

    /**
     * @throws UnsupportedCurrencyException if the code is unknown or not configured in {@code nfv.money.currencies}
     */
    public Currency getSupportedCurrency(String currencyCode) {
        if (currencyCode == null || !supportedCurrencies.contains(currencyCode)) {
            throw new UnsupportedCurrencyException(currencyCode);
        }
        return Currency.getInstance(currencyCode);
    }

    /**
     * Currency of a response: the requested one (e.g. query parameter {@code currency}), else the user's preferred
     * currency, else the base currency.
     */
    public Currency getResponseCurrency(@Nullable String requested, @Nullable String preferred) {
        if (requested != null && !requested.isBlank()) {
            return getSupportedCurrency(requested);
        }
        if (preferred != null && supportedCurrencies.contains(preferred)) {
            return Currency.getInstance(preferred);
        }
        return Money.BASE_CURRENCY;
    }

    /**
     * Converts an incoming amount with the latest known rate, regardless of its age. Use
     * {@link #toBaseForBooking(CurrencyAmount)} for conversions with booking effect.
     */
    public CurrencyConversion toBase(CurrencyAmount amount) {
        return toBase(amount, false);
    }

    /**
     * Like {@link #toBase(CurrencyAmount)}, but rejects exchange rates older than
     * {@code nfv.money.exchange-rates.max-age-days}. Store the returned conversion with the booking.
     */
    public CurrencyConversion toBaseForBooking(CurrencyAmount amount) {
        return toBase(amount, true);
    }

    public Money fromDto(MoneyDto dto) {
        return toBase(new CurrencyAmount(dto.amount(), getSupportedCurrency(dto.currency()))).base();
    }

    /**
     * Converts an amount of the base currency with the latest rate, for current values.
     */
    public MoneyDto toDto(Money money, Currency target, MoneyRounding rounding) {
        return toDto(money, target, rounding, null);
    }

    /**
     * Converts an amount of the base currency with the rate valid at {@code at}, for historical values.
     */
    public MoneyDto toDto(Money money, Currency target, MoneyRounding rounding, @Nullable LocalDate at) {
        getSupportedCurrency(target.getCurrencyCode());
        if (isBase(target)) {
            return new MoneyDto(rounding.apply(money.amount(), target), target.getCurrencyCode());
        }
        ExchangeRate rate = (at == null) ? getLatestRate(target) : getRateAt(target, at);
        BigDecimal amount = rounding.apply(money.amount().multiply(rate.getRate()), target);
        return new MoneyDto(amount, target.getCurrencyCode(), rate.getRate(), rate.getRateDate());
    }

    public void clearCache() {
        latestRates.clear();
    }

    private CurrencyConversion toBase(CurrencyAmount amount, boolean booking) {
        Currency currency = getSupportedCurrency(amount.currency().getCurrencyCode());
        if (isBase(currency)) {
            return new CurrencyConversion(amount, Money.of(amount.amount()), null, null);
        }
        ExchangeRate rate = getLatestRate(currency);
        if (booking && ChronoUnit.DAYS.between(rate.getRateDate(), today()) > maxRateAgeDays) {
            throw new ExchangeRateUnavailableException("Exchange rate for " + currency + " from " + rate.getRateDate()
                    + " is older than " + maxRateAgeDays + " days");
        }
        Money base = Money.of(amount.amount().divide(rate.getRate(), MathContext.DECIMAL128));
        return new CurrencyConversion(amount, base, rate.getRate(), rate.getRateDate());
    }

    private ExchangeRate getLatestRate(Currency currency) {
        return latestRates.computeIfAbsent(currency.getCurrencyCode(), _ -> getRateAt(currency, today()));
    }

    private ExchangeRate getRateAt(Currency currency, LocalDate date) {
        return exchangeRateRepository
                .findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(currency.getCurrencyCode(), date)
                .orElseThrow(() -> new ExchangeRateUnavailableException("No exchange rate for " + currency + " at " + date));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static boolean isBase(Currency currency) {
        return currency.equals(Money.BASE_CURRENCY);
    }

    private static String baseCode() {
        return Money.BASE_CURRENCY.getCurrencyCode();
    }
}
