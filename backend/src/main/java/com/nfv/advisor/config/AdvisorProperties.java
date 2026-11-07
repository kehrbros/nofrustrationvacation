package com.nfv.advisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Limits of the agent, see Docs/advisor.md#konfiguration.
 *
 * @param maxTurns     requests to the model per run, in the last one it has to finish
 * @param maxProposals most proposals per run
 * @param runTimeout   an unfinished run older than this counts as abandoned
 * @param autoEnabled  {@code RouteTooLate} starts runs, those of the user work either way. Off until the loop was
 *                     tested against the real API and the privacy notice exists
 * @param stopoverEnabled the model is offered {@code find_overnight_areas}. Off until {@code search_accommodations}
 *                     exists: the model cannot do anything with a place on its own
 */
@ConfigurationProperties("nfv.advisor")
public record AdvisorProperties(
        @DefaultValue("8") int maxTurns,
        @DefaultValue("3") int maxProposals,
        @DefaultValue("15m") Duration runTimeout,
        @DefaultValue("false") boolean autoEnabled,
        @DefaultValue("false") boolean stopoverEnabled) {
}
