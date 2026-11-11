package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.RouteResult;
import com.nfv.network.logic.RoutedSegment;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Way a generator proposes: one route result per leg (start → via → … → end), see
 * Docs/recommendations.md#generators.
 */
record Candidate(OptionKind kind, List<RouteResult> legs, OffsetDateTime departure) {
    double distanceMeters() {
        return legs.stream().mapToDouble(RouteResult::distanceMeters).sum();
    }

    Duration duration() {
        return legs.stream().map(RouteResult::duration).reduce(Duration.ZERO, Duration::plus);
    }

    /** Segments of all legs in driving order. */
    List<RoutedSegment> segments() {
        return legs.stream().flatMap(leg -> leg.segments().stream()).toList();
    }

    /** Geometry of all legs, the joint point between two legs only once. */
    List<GeoPoint> geometry() {
        List<GeoPoint> points = new ArrayList<>();
        for (RouteResult leg : legs) {
            List<GeoPoint> line = leg.geometry().points();
            points.addAll(points.isEmpty() || line.isEmpty() || !points.getLast().equals(line.getFirst())
                    ? line : line.subList(1, line.size()));
        }
        return points;
    }
}