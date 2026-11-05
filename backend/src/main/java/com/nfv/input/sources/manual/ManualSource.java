package com.nfv.input.sources.manual;

import com.nfv.incidents.logic.IncidentLocation;
import com.nfv.incidents.logic.TimeWindow;
import com.nfv.input.config.InputProperties;
import com.nfv.input.entities.EventSourceType;
import com.nfv.input.logic.Attribution;
import com.nfv.input.logic.FetchContext;
import com.nfv.input.logic.PollPolicy;
import com.nfv.input.logic.SourceSnapshot;
import com.nfv.input.logic.SourceItem;
import com.nfv.input.logic.TrafficReport;
import com.nfv.input.logic.TrafficSource;
import jakarta.annotation.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test source: events entered by hand go through the same flow as those of a real source, see
 * Docs/traffic-feed.md#testquelle-manual. Only exists with {@code nfv.input.sources.manual.enabled=true}. Events are
 * kept in memory until polled again; the snapshot is never complete, so a restart clears nothing. An event ends by
 * posting it with {@code resolved}.
 */
@Component
@ConditionalOnProperty(name = "nfv.input.sources.manual.enabled", havingValue = "true")
public class ManualSource implements TrafficSource {
    public static final String NAME = "manual";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** Events are polled when posted, the schedule only looks now and then. */
    private static final PollPolicy POLL_POLICY = new PollPolicy(Duration.ofHours(1), Duration.ZERO,
            Duration.ofHours(1), Duration.ofSeconds(1), 0, 0, 0);
    private static final Attribution ATTRIBUTION = new Attribution("Test", "Von Hand eingegebene Testereignisse.",
            Duration.ofDays(365));

    private final Map<String, SourceItem> items = new ConcurrentHashMap<>();
    private final AtomicLong versions = new AtomicLong();
    private final String networkRegion;
    private final Clock clock;

    public ManualSource(InputProperties properties, Clock clock) {
        InputProperties.Source config = properties.source(NAME);
        this.networkRegion = config != null && config.networkRegion() != null ? config.networkRegion() : "nz";
        this.clock = clock;
    }

    /** Takes over the event as a new version, seen by the next poll. */
    public void add(ManualTrafficEvent event) {
        String version = clock.instant() + "#" + versions.incrementAndGet();
        items.put(event.id(), new SourceItem(event.id(), version, MAPPER.writeValueAsString(event), event.text()));
    }

    @Override
    public SourceSnapshot fetch(FetchContext context) {
        return new SourceSnapshot(List.copyOf(items.values()), false, null, null, false);
    }

    @Override
    public TrafficReport read(String payload) {
        ManualTrafficEvent event;
        try {
            event = MAPPER.readValue(payload, ManualTrafficEvent.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Unreadable manual event: " + e.getOriginalMessage(), e);
        }
        return new TrafficReport(event.type(), event.resolved(), event.validFrom(), event.validTo(), event.roadRef(),
                event.direction(), location(event), minutes(event.delayMinutes()),
                minutes(event.assumedDelayMinutes()), "Test: " + event.id(), event.text(), event.text(), null,
                event.linkOnly(), windows(event), event.vehicleScope());
    }

    private static IncidentLocation location(ManualTrafficEvent event) {
        if (event.point() != null) {
            return new IncidentLocation.Point(coordinate(event.point()));
        }
        return new IncidentLocation.Lines(List.of(event.line().stream().map(ManualSource::coordinate).toList()));
    }

    private static IncidentLocation.Coordinate coordinate(ManualTrafficEvent.Position position) {
        return new IncidentLocation.Coordinate(position.lat(), position.lon());
    }

    private static List<TimeWindow> windows(ManualTrafficEvent event) {
        return event.windows() == null ? List.of()
                : event.windows().stream().map(window -> new TimeWindow(window.start(), window.end())).toList();
    }

    private static @Nullable Duration minutes(@Nullable Long minutes) {
        return minutes == null ? null : Duration.ofMinutes(minutes);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String country() {
        return "NZ";
    }

    @Override
    public String networkRegion() {
        return networkRegion;
    }

    @Override
    public ZoneId zone() {
        return ZoneId.of("Pacific/Auckland");
    }

    @Override
    public EventSourceType type() {
        return EventSourceType.TRAFFIC_FEED;
    }

    @Override
    public PollPolicy pollPolicy() {
        return POLL_POLICY;
    }

    @Override
    public Attribution attribution() {
        return ATTRIBUTION;
    }
}
