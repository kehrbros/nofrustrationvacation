package com.vacantagent.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings under {@code vacantagent.market.*} in application.properties.
 *
 * @param maxPrice        highest allowed price per million tokens, in micro units (see {@code Prices})
 * @param inboxCapacity   commands that may wait for the matching thread before submitters block
 * @param writerBatchSize agreements written to the database per batch
 * @param agreementIdleTimeout an agreement without a call for this long is idle: calls are refused and its offer
 *                             re-enters the market
 * @param agreementSweepInterval how often offers of agreements that became idle are released
 * @param expirySweepInterval    how often expired offers and orders are removed from the book
 */
@ConfigurationProperties(prefix = "vacantagent.market")
public record MarketProperties(
        @DefaultValue("1000000000") long maxPrice,
        @DefaultValue("100000") int inboxCapacity,
        @DefaultValue("1000") int writerBatchSize,
        @DefaultValue("30s") Duration agreementIdleTimeout,
        @DefaultValue("5s") Duration agreementSweepInterval,
        @DefaultValue("10s") Duration expirySweepInterval) {
}