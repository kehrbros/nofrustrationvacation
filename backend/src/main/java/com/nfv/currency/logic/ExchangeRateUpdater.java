package com.nfv.currency.logic;

import com.nfv.common.money.Money;
import com.nfv.currency.data.ExchangeRateRepository;
import com.nfv.currency.entities.ExchangeRate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Fetches the exchange rates on startup and daily after the ECB publication (around 16:00 CET).
 * Failures are logged only: conversions use the latest stored rate, see Docs/money.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nfv.money.exchange-rates.update-enabled", havingValue = "true", matchIfMissing = true)
public class ExchangeRateUpdater {
    private final ExchangeRateProvider exchangeRateProvider;
    private final ExchangeRateRepository exchangeRateRepository;
    private final CurrencyConversionService currencyConversionService;
    private final Clock clock;

    @EventListener(ApplicationReadyEvent.class)
    public void updateOnStartup() {
        update();
    }

    @Scheduled(cron = "${nfv.money.exchange-rates.update-cron}", zone = "Europe/Berlin")
    public void updateScheduled() {
        update();
    }

    void update() {
        try {
            int stored = store();
            log.info("Stored {} new exchange rates from {}", stored, exchangeRateProvider.source());
        } catch (RuntimeException e) {
            log.warn("Updating exchange rates from {} failed, keeping the latest stored rates", exchangeRateProvider.source(), e);
        }
    }

    private int store() {
        OffsetDateTime fetchedAt = OffsetDateTime.now(clock);
        int stored = 0;
        for (ExchangeRateQuote quote : exchangeRateProvider.fetchLatest()) {
            String currency = quote.currency().getCurrencyCode();
            if (!currency.equals(Money.BASE_CURRENCY.getCurrencyCode())
                    && !exchangeRateRepository.existsByCurrencyAndRateDate(currency, quote.rateDate())) {
                exchangeRateRepository.save(new ExchangeRate(currency, quote.rate(), quote.rateDate(), fetchedAt,
                        exchangeRateProvider.source()));
                stored++;
            }
        }
        currencyConversionService.clearCache();
        return stored;
    }
}
