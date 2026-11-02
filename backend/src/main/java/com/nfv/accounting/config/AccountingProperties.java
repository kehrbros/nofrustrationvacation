package com.nfv.accounting.config;

import com.nfv.common.money.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Business limits of the accounting, see Docs/accounting.md. Amounts are in the base currency.
 *
 * @param minTopUp           smallest top-up
 * @param maxTopUp           largest single top-up
 * @param minPayout          smallest payout
 * @param linkStateTtl       time the user has to confirm the PayPal account after starting the link
 * @param statusCheckEnabled poll PayPal for pending top-ups and submitted payouts
 */
@ConfigurationProperties("nfv.accounting")
public record AccountingProperties(Money minTopUp, Money maxTopUp, Money minPayout, Duration linkStateTtl,
                                   boolean statusCheckEnabled) {
}
