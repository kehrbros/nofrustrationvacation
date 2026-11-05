package com.nfv.input.logic;

import com.nfv.input.config.InputProperties;
import com.nfv.input.data.EventSourceRepository;
import com.nfv.input.entities.EventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All {@link TrafficSource} beans. Creates missing {@link EventSource}s on start; a source is active only if both
 * {@code EventSource.enabled} and {@code nfv.input.sources.<name>.enabled} are set.
 */
@Component
public class TrafficSources {
    private static final Logger LOG = LoggerFactory.getLogger(TrafficSources.class);

    private final Map<String, TrafficSource> sources = new LinkedHashMap<>();
    private final EventSourceRepository eventSourceRepository;
    private final InputProperties properties;

    public TrafficSources(List<TrafficSource> sources, EventSourceRepository eventSourceRepository,
                          InputProperties properties) {
        for (TrafficSource source : sources) {
            if (this.sources.putIfAbsent(source.name(), source) != null) {
                throw new IllegalStateException("Two traffic sources named " + source.name());
            }
        }
        this.eventSourceRepository = eventSourceRepository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void register() {
        sources.values().forEach(this::eventSource);
    }

    public List<TrafficSource> all() {
        return List.copyOf(sources.values());
    }

    public Optional<TrafficSource> find(String name) {
        return Optional.ofNullable(sources.get(name));
    }

    /** The {@link EventSource} of the adapter, created if missing. */
    @Transactional
    public EventSource eventSource(TrafficSource source) {
        return eventSourceRepository.findByName(source.name()).orElseGet(() -> {
            LOG.info("Registering traffic source {} ({})", source.name(), source.type());
            return eventSourceRepository.save(new EventSource(source.name(), source.type()));
        });
    }

    /** Enabled in the configuration and in the database. */
    @Transactional(readOnly = true)
    public boolean isEnabled(TrafficSource source) {
        InputProperties.Source config = properties.source(source.name());
        return config != null && config.enabled()
                && eventSourceRepository.findByName(source.name()).map(EventSource::isEnabled).orElse(true);
    }
}
