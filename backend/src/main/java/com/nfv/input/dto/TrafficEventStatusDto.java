package com.nfv.input.dto;

import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.entities.MatchKind;
import com.nfv.incidents.entities.VehicleScope;
import com.nfv.incidents.logic.IncidentOverview;
import com.nfv.incidents.logic.RoutePlacement;
import com.nfv.incidents.logic.TimeWindow;
import com.nfv.input.logic.TrafficEventInspection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "What became of the latest version of a traffic event")
public record TrafficEventStatusDto(
        String source,
        String id,
        String version,
        @Schema(description = "PENDING, INCIDENT or the reason without incident, e.g. NO_SEGMENTS") String outcome,
        @Schema(description = "Note on the processing, e.g. TIMES_IN_TEXT") @Nullable String note,
        @Nullable OffsetDateTime processedAt,
        @Schema(description = "null if this version led to no incident") @Nullable IncidentDto incident,
        @Schema(description = "Where the point was placed onto the route (onRoute), null otherwise")
        @Nullable PlacementDto placement) {

    public record IncidentDto(long id, IncidentType type, Impact impact, @Nullable String description,
                              OffsetDateTime validFrom, @Nullable OffsetDateTime validTo,
                              @Nullable Long expectedDelayMinutes, @Nullable Long assumedDelayMinutes,
                              boolean timesUnconfirmed, VehicleScope vehicleScope, List<WindowDto> windows,
                              int segments, List<RoadDto> roads,
                              @Schema(description = "On the routes of all users, also ones already passed")
                              List<ConsequenceDto> consequences) {
    }

    public record WindowDto(OffsetDateTime start, OffsetDateTime end) {
    }

    public record RoadDto(@Nullable String ref, @Nullable String name, int segments, long lengthMeters) {
    }

    public record ConsequenceDto(long routeId, @Nullable Category category, MatchKind match, Impact impact,
                                 @Nullable Long assessedDelayMinutes, boolean delayAssumed,
                                 @Nullable OffsetDateTime passageAt, @Nullable OffsetDateTime expectedArrival,
                                 @Nullable OffsetDateTime deadline, @Nullable OffsetDateTime windowStart,
                                 @Nullable OffsetDateTime windowEnd) {
    }

    @Schema(description = "A tapped point placed onto a planned route")
    public record PlacementDto(long routeId, double lat, double lon,
                               @Schema(description = "From the tapped point") long distanceMeters,
                               @Schema(description = "Road number there, null if none") @Nullable String roadRef,
                               @Nullable String roadName,
                               @Schema(description = "Planned passage of the route there") OffsetDateTime passageStart,
                               OffsetDateTime passageEnd) {
        public static PlacementDto of(RoutePlacement.Placement placement) {
            return new PlacementDto(placement.routeId(), placement.point().lat(), placement.point().lon(),
                    Math.round(placement.distanceMeters()), placement.roadRef(), placement.roadName(),
                    placement.passageStart(), placement.passageEnd());
        }
    }

    public static TrafficEventStatusDto of(TrafficEventInspection.EventStatus status) {
        return of(status, null);
    }

    public static TrafficEventStatusDto of(TrafficEventInspection.EventStatus status,
                                           @Nullable RoutePlacement.Placement placement) {
        return new TrafficEventStatusDto(status.source(), status.subjectId(), status.externalId(), status.outcome(),
                status.note(), status.processedAt(), status.incident() == null ? null : incident(status.incident()),
                placement == null ? null : PlacementDto.of(placement));
    }

    private static IncidentDto incident(IncidentOverview incident) {
        return new IncidentDto(incident.id(), incident.type(), incident.impact(), incident.description(),
                incident.validFrom(), incident.validTo(), minutes(incident.expectedDelay()),
                minutes(incident.assumedDelay()), incident.timesUnconfirmed(), incident.vehicleScope(),
                incident.windows().stream().map(TrafficEventStatusDto::window).toList(), incident.segments(),
                incident.roads().stream().map(road -> new RoadDto(road.ref(), road.name(), road.segments(),
                        Math.round(road.lengthMeters()))).toList(),
                incident.consequences().stream().map(TrafficEventStatusDto::consequence).toList());
    }

    private static WindowDto window(TimeWindow window) {
        return new WindowDto(window.startsAt(), window.endsAt());
    }

    private static ConsequenceDto consequence(IncidentOverview.Effect effect) {
        return new ConsequenceDto(effect.routeId(), effect.category(), effect.match(), effect.impact(),
                minutes(effect.assessedDelay()), effect.delayAssumed(), effect.passageAt(), effect.expectedArrival(),
                effect.deadline(), effect.windowStart(), effect.windowEnd());
    }

    private static @Nullable Long minutes(@Nullable Duration duration) {
        return duration == null ? null : duration.toMinutes();
    }
}
