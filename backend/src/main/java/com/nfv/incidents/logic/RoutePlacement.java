package com.nfv.incidents.logic;

import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.SectionPart;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.entities.RouteSection;
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Places a spot tapped next to a planned route onto the route itself (nearest point of its sections, PostGIS), e.g.
 * for test incidents, see Docs/traffic-feed.md#testquelle-manual. A point on the route lies on the segment the route
 * drives, so locating it ({@code nfv.incidents.point-radius-meters}) always finds that segment.
 */
@Service
@RequiredArgsConstructor
public class RoutePlacement {
    /** Farther away the tap is not meant for this route. */
    public static final double MAX_DISTANCE_METERS = 5_000;

    private final TripLogic tripLogic;
    private final RouteSectionRepository routeSectionRepository;
    private final SegmentQueries segmentQueries;

    /**
     * @param point          nearest point on the route
     * @param distanceMeters from the tap to it
     * @param roadRef        road number of the segment there, {@code null} if it has none
     * @param roadName       name of the road there, {@code null} if it has none
     * @param passageStart   expected passage of the section there, as planned
     */
    public record Placement(long routeId, IncidentLocation.Coordinate point, double distanceMeters,
                            @Nullable String roadRef, @Nullable String roadName, OffsetDateTime passageStart,
                            OffsetDateTime passageEnd) {
    }

    /**
     * The tap placed onto the route, empty if the trip or route is unknown or of another user, the route is not
     * planned, or the tap is farther than {@link #MAX_DISTANCE_METERS} away.
     */
    @Transactional(readOnly = true)
    public Optional<Placement> place(long appUserId, long tripId, long routeId, IncidentLocation.Coordinate tap) {
        // no exception for a foreign route: it would mark this transaction rollback-only
        if (!Long.valueOf(tripId).equals(tripLogic.tripIdsByRouteId(appUserId).get(routeId))) {
            return Optional.empty();
        }
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        List<SectionPart> parts = sections.stream()
                .map(s -> new SectionPart(s.getSegmentId(), s.getFromFraction(), s.getToFraction()))
                .toList();
        return segmentQueries.closest(parts, new GeoPoint(tap.lat(), tap.lon()))
                .filter(closest -> closest.distanceMeters() <= MAX_DISTANCE_METERS)
                .map(closest -> {
                    RouteSection section = sections.get(closest.index());
                    SegmentInfo segment = segmentQueries.segments(Set.of(section.getSegmentId()))
                            .get(section.getSegmentId());
                    return new Placement(routeId,
                            new IncidentLocation.Coordinate(closest.point().lat(), closest.point().lon()),
                            closest.distanceMeters(), segment == null ? null : segment.ref(),
                            segment == null ? null : segment.name(), section.getExpectedPassageStart(),
                            section.getExpectedPassageEnd());
                });
    }
}
