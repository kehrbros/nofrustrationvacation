package com.nfv.input.sources.manual;

import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.entities.VehicleScope;
import com.nfv.incidents.logic.TravelDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

/** An event entered by hand, the request and also the stored payload of {@link ManualSource}. */
@Schema(description = "Traffic event entered by hand. Posting the same id again changes the event.")
public record ManualTrafficEvent(
        @Schema(description = "Id of the event, the same for all its versions") String id,
        IncidentType type,
        @Schema(description = "A spot; exactly one of point and line") @Nullable Position point,
        @Schema(description = "A stretch in driving order, at least 2 positions") @Nullable List<Position> line,
        @Schema(description = "Road number, e.g. SH 1 or 01N; null: any road at that place") @Nullable String roadRef,
        @Schema(description = "null: both directions") @Nullable TravelDirection direction,
        OffsetDateTime validFrom,
        @Schema(description = "null: until further notice") @Nullable OffsetDateTime validTo,
        @Schema(description = "Delay as a figure") @Nullable Long delayMinutes,
        @Schema(description = "Delay assumed by the source without a figure, shown as estimated")
        @Nullable Long assumedDelayMinutes,
        @Schema(description = "Free text. Times of day in it without windows make a closure unconfirmed, as for "
                + "other sources") @Nullable String text,
        @Schema(description = "Periods within the validity, as the enrichment would give them") @Nullable List<Window> windows,
        @Schema(description = "As the enrichment would give it, null: all vehicles") @Nullable VehicleScope vehicleScope,
        @Schema(description = "Only a ramp or link, not the road itself, default false") Boolean linkOnly,
        @Schema(description = "The event has ended, default false") Boolean resolved,
        @Schema(description = "Place the point onto this planned route of the caller first (nearest point, at most "
                + "5 km away), so the incident lies on the segment the route drives; null: the point as given")
        @Nullable OnRoute onRoute) {

    public ManualTrafficEvent {
        linkOnly = Boolean.TRUE.equals(linkOnly);
        resolved = Boolean.TRUE.equals(resolved);
    }

    public record Position(double lat, double lon) {
    }

    public record Window(OffsetDateTime start, OffsetDateTime end) {
    }

    public record OnRoute(long tripId, long routeId) {
    }

    /** The same event at another point, without {@code onRoute}: placed already. */
    public ManualTrafficEvent at(Position placed) {
        return new ManualTrafficEvent(id, type, placed, null, roadRef, direction, validFrom, validTo, delayMinutes,
                assumedDelayMinutes, text, windows, vehicleScope, linkOnly, resolved, null);
    }

    /** @return what is wrong with the event, {@code null} if it can be reported */
    public @Nullable String problem() {
        if (id == null || id.isBlank() || id.length() > 100) {
            return "id is required, at most 100 characters";
        }
        if (type == null) {
            return "type is required";
        }
        if (validFrom == null) {
            return "validFrom is required";
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            return "validTo is before validFrom";
        }
        if ((point == null) == (line == null)) {
            return "exactly one of point and line is required";
        }
        if (onRoute != null && point == null) {
            return "onRoute needs a point";
        }
        if (line != null && line.size() < 2) {
            return "line needs at least 2 positions";
        }
        if (windows != null && windows.stream().anyMatch(window -> window.start() == null || window.end() == null
                || !window.end().isAfter(window.start()))) {
            return "every window needs a start and a later end";
        }
        return null;
    }
}
