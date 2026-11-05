package com.nfv.trips.logic;

import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.SectionPart;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.data.WaypointRepository;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.RouteSection;
import com.nfv.trips.entities.Waypoint;
import com.nfv.trips.entities.WaypointOrigin;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** The planned ways of the routes of a trip as lines for the map, see Docs/domain.md#trips. */
@Service
@RequiredArgsConstructor
public class RouteLines {
    private final TripLogic tripLogic;
    private final RouteSectionRepository routeSectionRepository;
    private final WaypointRepository waypointRepository;
    private final SegmentQueries segmentQueries;

    /**
     * @param distanceMeters driven length of the sections
     * @param duration       from entering the first section to leaving the last
     * @param via            road hints of the waypoints created from a suggestion, e.g. {@code SH 2}, {@code null}
     *                       if there are none
     */
    public record RouteLine(long routeId, List<GeoPoint> points, double distanceMeters, Duration duration,
                            @Nullable String via) {
    }

    /**
     * Lines of the planned routes of the trip; routes without sections (not planned, planning failed) are missing.
     *
     * @throws TripNotFoundException trip unknown or of another user
     */
    @Transactional(readOnly = true)
    public List<RouteLine> of(long appUserId, long tripId) {
        List<RouteLine> lines = new ArrayList<>();
        for (Route route : tripLogic.routes(appUserId, tripId)) {
            List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(route.getId());
            if (sections.isEmpty()) {
                continue;
            }
            List<SectionPart> parts = sections.stream()
                    .map(s -> new SectionPart(s.getSegmentId(), s.getFromFraction(), s.getToFraction()))
                    .toList();
            Map<Long, SegmentInfo> segments = segmentQueries.segments(parts.stream().map(SectionPart::segmentId)
                    .collect(Collectors.toSet()));
            double distance = parts.stream().mapToDouble(p -> {
                SegmentInfo info = segments.get(p.segmentId());
                return info == null ? 0 : info.lengthMeters() * (p.toFraction() - p.fromFraction());
            }).sum();
            Duration duration = Duration.between(sections.getFirst().getExpectedPassageStart(),
                    sections.getLast().getExpectedPassageEnd());
            String via = waypointRepository.findByRouteIdOrderByPosition(route.getId()).stream()
                    .filter(w -> w.getOrigin() == WaypointOrigin.SUGGESTION)
                    .map(Waypoint::getRoadHint).filter(Objects::nonNull).distinct()
                    .collect(Collectors.collectingAndThen(Collectors.joining(", "), v -> v.isEmpty() ? null : v));
            lines.add(new RouteLine(route.getId(), segmentQueries.line(parts).points(), distance, duration, via));
        }
        return lines;
    }
}
