package com.nfv.input.logic;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** No model yet: no enrichment, events with times in their text stay {@code OTHER} (stage 2 rule). */
@Component
public class NoEnrichmentModel implements EnrichmentModel {
    @Override
    public Optional<Answer> extract(String messageText, TrafficReport report) {
        return Optional.empty();
    }
}
