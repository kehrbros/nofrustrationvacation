package com.vacantagent.market.logic;

import com.vacantagent.market.entities.ProviderOffer;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class MarketRules {

    public static final long PROVIDER_ERROR_BACKOFF_MILLIS = 60_000L;

    public boolean isProviderOfferAvailable(ProviderOffer providerOffer) {
        return providerOffer.getLastErrorAt().isBefore(OffsetDateTime.now().plus(PROVIDER_ERROR_BACKOFF_MILLIS, ChronoUnit.MILLIS));
    }
}
