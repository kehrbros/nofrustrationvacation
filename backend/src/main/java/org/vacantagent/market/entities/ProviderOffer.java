package org.vacantagent.market.entities;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

@Getter
@Setter
public class ProviderOffer {
    private BigDecimal costPerToken;
    // private BigDecimal lowerLimit for negotiation
    private String modelProvider;
    private String modelName;
    private Duration marketWaitTime;
    private OffsetDateTime lastErrorAt;

    private boolean isProviderOfferAvailable(){
        return false;
    }
}
