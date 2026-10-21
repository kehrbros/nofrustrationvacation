package com.vacantagent.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code vacantagent.market.*} in application.properties.
 *
 * @param maxPrice        highest allowed price per million tokens, in micro units (see {@code Prices})
 * @param inboxCapacity   commands that may wait for the matching thread before submitters block
 * @param writerBatchSize agreements written to the database per batch
 */
@ConfigurationProperties(prefix = "vacantagent.market")
public record MarketProperties(
        @DefaultValue("1000000000") long maxPrice,
        @DefaultValue("100000") int inboxCapacity,
        @DefaultValue("1000") int writerBatchSize) {
}