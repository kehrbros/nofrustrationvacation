package com.nfv.input.logic;

import com.nfv.input.entities.EventSourceType;

import java.time.ZoneId;

/**
 * Adapter of one traffic source, the only source-specific code, see Docs/traffic-feed.md#schnittstelle-trafficsource-inputlogic.
 * Implementations are beans in {@code input.sources.<country>} and know only the records of {@code input.logic}.
 * {@code parse} (webhooks) follows with the first webhook source.
 */
public interface TrafficSource {
    /** Unique name, = {@code EventSource.name}, e.g. {@code nz-nzta}. */
    String name();

    /** ISO 3166 code, e.g. {@code NZ}. */
    String country();

    /** Region of the road network to locate events in, default of the adapter, overridable by configuration. */
    String networkRegion();

    /** Time zone of the region, local times in texts (time windows) are converted with it, e.g. Pacific/Auckland. */
    ZoneId zone();

    EventSourceType type();

    /** Only for {@code TRAFFIC_FEED}, otherwise {@link PollPolicy#NONE}. */
    PollPolicy pollPolicy();

    Attribution attribution();

    /**
     * Whole current state of the source. Only {@code TRAFFIC_FEED}.
     *
     * @throws SourceUnavailableException if the source cannot be reached or answers with an error or unreadable data
     */
    SourceSnapshot fetch(FetchContext context);

    /**
     * Reads one stored event ({@link SourceItem#payload()}, as kept in {@code input_event.payload}), deterministic,
     * without network and without model.
     *
     * @throws IllegalArgumentException if the payload cannot be read
     */
    TrafficReport read(String payload);
}
