package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoLine;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.RouteResult;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.network.logic.Routing;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.recommendations.config.RecommendationProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Via points on a small network where planning over a badly placed point drives the old way to it and back (a spur),
 * like Kingston → Nevis Road: the fast way passes a closure, the detour is slow and starts far from the fast way.
 */
class ViaPointExtractorTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2026, 11, 20, 8, 0, 0, 0, ZoneOffset.ofHours(13));

    /**
     * S start, T junction, Z target far away. Fast way S–A–T (20 min, with the closure), detour S–D1–D2–D3–T
     * (95 min) that meets the fast way close to T, the long way on T–Z is shared. Minutes = kilometres.
     */
    private final Network network = new Network()
            .road("S", "A", 10).road("A", "T", 10)
            .road("S", "D1", 30).road("D1", "D2", 25).road("D2", "D3", 35).road("D3", "T", 5)
            .road("T", "Z", 900);

    private final Routing routing = network.routing();
    private final ViaPointExtractor extractor = new ViaPointExtractor(routing, properties());

    @Test
    void pointsKeepTheRouteOnTheDetourWithoutSpur() {
        Candidate detour = network.candidate("S", "D1", "D2", "D3", "T", "Z");
        Candidate fast = network.candidate("S", "A", "T", "Z");

        List<SuggestedOption.Point> points = extractor.extract(scenario(), detour, fast, network.segments());

        assertThat(points).isNotEmpty();
        List<RoutedSegment> planned = plannedOver(points);
        Set<Long> detourIds = ids(detour.segments());
        // as planned after choosing: every segment lies on the detour, nothing of the fast way or back
        assertThat(planned).allMatch(segment -> detourIds.contains(segment.segmentId()));
        assertThat(ids(planned)).containsAll(detourIds);
    }

    @Test
    void theOldRatioAloneWouldAcceptTheSpur() {
        // the point in the middle of D2–D3 is reached over A and T and left the same way back
        Candidate detour = network.candidate("S", "D1", "D2", "D3", "T", "Z");
        GeoPoint middle = network.segments().get(network.id("D2", "D3")).midpoint();
        List<RoutedSegment> planned = plannedOver(List.of(new SuggestedOption.Point(middle.lat(), middle.lon(),
                null, null)));

        assertThat(ids(planned)).contains(network.id("S", "A"), network.id("D3", "D2"));
        assertThat(CandidateEvaluator.sharedRatio(ViaPointExtractor.steps(detour.segments()), ids(planned)))
                .isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void sameWayAsTheFastestNeedsNoPoints() {
        Candidate fast = network.candidate("S", "A", "T", "Z");

        assertThat(extractor.extract(scenario(), fast, fast, network.segments())).isEmpty();
    }

    @Test
    void middleOfTheRunFirstShortSegmentsLast() {
        List<RoutedSegment> segments = List.of(segment(1, 10), segment(2, 0.5), segment(3, 10), segment(4, 10));

        assertThat(ViaPointExtractor.byDistanceToMiddle(segments, List.of(0, 1, 2, 3))).containsExactly(2, 0, 3, 1);
    }

    private List<RoutedSegment> plannedOver(List<SuggestedOption.Point> points) {
        List<RoutePoint> route = new ArrayList<>();
        route.add(new RoutePoint(network.at("S"), RoutePoint.Kind.STOP, null));
        points.forEach(p -> route.add(new RoutePoint(new GeoPoint(p.latitude(), p.longitude()), RoutePoint.Kind.VIA,
                null)));
        route.add(new RoutePoint(network.at("Z"), RoutePoint.Kind.STOP, null));
        return routing.route(route, RouteOptions.none()).segments();
    }

    private Scenario scenario() {
        return new Scenario(1, SuggestionTrigger.INCIDENT, 2L, new RoutePoint(network.at("S"), RoutePoint.Kind.STOP,
                null), new RoutePoint(network.at("Z"), RoutePoint.Kind.STOP, null), List.of(), WaypointMode.FOLLOW,
                DEPARTURE, RouteOptions.none(), List.of(), Set.of(OptionKind.ALTERNATIVES), 3, List.of(), null);
    }

    private static RecommendationProperties properties() {
        return new RecommendationProperties(3, 5, 1.4, 0.6, 0.6, 0.95, 0.9, 2000, 3, 12, 25, Duration.ofMinutes(15),
                Duration.ofHours(24), new RecommendationProperties.Stopover(LocalTime.of(22, 0), 5000, 15000, 3, 5));
    }

    private static RoutedSegment segment(long id, double kilometres) {
        return new RoutedSegment(id, 0, 1, kilometres * 1000, Duration.ofMinutes(Math.round(kilometres)));
    }

    private static Set<Long> ids(List<RoutedSegment> segments) {
        Set<Long> ids = new HashSet<>();
        segments.forEach(segment -> ids.add(segment.segmentId()));
        return ids;
    }

    /**
     * Roads in both directions, one segment per direction. Routing like GraphHopper without restrictions: fastest way
     * between consecutive points, a via point is the middle of a road and may be left the way it was reached.
     */
    private static final class Network {
        private final Map<String, GeoPoint> nodes = new LinkedHashMap<>();
        /** Directed segment by "from>to". */
        private final Map<String, Long> ids = new HashMap<>();
        private final Map<Long, String[]> ends = new HashMap<>();
        private final Map<Long, Double> minutes = new HashMap<>();
        private long next = 1;

        Network road(String from, String to, double length) {
            node(from);
            node(to);
            long forward = next++;
            long backward = next++;
            ids.put(from + ">" + to, forward);
            ids.put(to + ">" + from, backward);
            ends.put(forward, new String[]{from, to});
            ends.put(backward, new String[]{to, from});
            minutes.put(forward, length);
            minutes.put(backward, length);
            return this;
        }

        private void node(String name) {
            // distinct positions, the geometry itself does not matter
            nodes.computeIfAbsent(name, _ -> new GeoPoint(-45 + nodes.size() * 0.01, 168 + nodes.size() * 0.01));
        }

        GeoPoint at(String node) {
            return nodes.get(node);
        }

        long id(String from, String to) {
            return ids.get(from + ">" + to);
        }

        Map<Long, SegmentInfo> segments() {
            Map<Long, SegmentInfo> infos = new HashMap<>();
            ends.forEach((id, pair) -> infos.put(id, new SegmentInfo(id, null, pair[0] + "-" + pair[1],
                    minutes.get(id) * 1000, middle(pair[0], pair[1]), id(pair[1], pair[0]))));
            return infos;
        }

        private GeoPoint middle(String from, String to) {
            GeoPoint a = nodes.get(from);
            GeoPoint b = nodes.get(to);
            return new GeoPoint((a.lat() + b.lat()) / 2, (a.lon() + b.lon()) / 2);
        }

        Candidate candidate(String... path) {
            List<RoutedSegment> segments = new ArrayList<>();
            for (int i = 0; i + 1 < path.length; i++) {
                segments.add(full(id(path[i], path[i + 1])));
            }
            return new Candidate(OptionKind.ALTERNATIVES, List.of(result(segments)), DEPARTURE);
        }

        private RoutedSegment full(long id) {
            double length = minutes.get(id);
            return new RoutedSegment(id, 0, 1, length * 1000, Duration.ofSeconds(Math.round(length * 60)));
        }

        private RoutedSegment half(long id, double from, double to) {
            double length = minutes.get(id) * (to - from);
            return new RoutedSegment(id, from, to, length * 1000, Duration.ofSeconds(Math.round(length * 60)));
        }

        private static RouteResult result(List<RoutedSegment> segments) {
            double distance = segments.stream().mapToDouble(RoutedSegment::distanceMeters).sum();
            Duration duration = segments.stream().map(RoutedSegment::duration).reduce(Duration.ZERO, Duration::plus);
            return new RouteResult("test", 1, distance, duration, new GeoLine(List.of()), segments, List.of());
        }

        Routing routing() {
            Routing routing = mock(Routing.class);
            when(routing.route(any(), any())).thenAnswer(call -> {
                List<RoutePoint> points = call.getArgument(0);
                List<RoutedSegment> segments = new ArrayList<>();
                for (int i = 0; i + 1 < points.size(); i++) {
                    segments.addAll(leg(points.get(i).point(), points.get(i + 1).point()));
                }
                return result(segments);
            });
            return routing;
        }

        /** A point is a node or the middle of a road (both directions of it). */
        private record Place(String node, Long forward, Long backward) {
        }

        private Place place(GeoPoint point) {
            for (Map.Entry<String, GeoPoint> node : nodes.entrySet()) {
                if (node.getValue().equals(point)) {
                    return new Place(node.getKey(), null, null);
                }
            }
            for (Map.Entry<Long, String[]> end : ends.entrySet()) {
                String[] pair = end.getValue();
                if (pair[0].compareTo(pair[1]) < 0 && middle(pair[0], pair[1]).equals(point)) {
                    return new Place(null, end.getKey(), id(pair[1], pair[0]));
                }
            }
            throw new IllegalArgumentException("not on the network: " + point);
        }

        private List<RoutedSegment> leg(GeoPoint fromPoint, GeoPoint toPoint) {
            Place from = place(fromPoint);
            Place to = place(toPoint);
            // starts: a node, or the middle of a road towards either end
            Map<String, Double> start = new HashMap<>();
            Map<String, RoutedSegment> first = new HashMap<>();
            if (from.node() != null) {
                start.put(from.node(), 0.0);
            } else {
                for (long id : List.of(from.forward(), from.backward())) {
                    String end = ends.get(id)[1];
                    start.put(end, minutes.get(id) / 2);
                    first.put(end, half(id, 0.5, 1));
                }
            }
            Map<String, Double> best = new HashMap<>();
            Map<String, Long> via = new HashMap<>();
            PriorityQueue<Map.Entry<String, Double>> queue = new PriorityQueue<>(Map.Entry.comparingByValue());
            start.forEach((node, cost) -> {
                best.put(node, cost);
                queue.add(Map.entry(node, cost));
            });
            while (!queue.isEmpty()) {
                Map.Entry<String, Double> current = queue.poll();
                if (current.getValue() > best.get(current.getKey())) {
                    continue;
                }
                for (Map.Entry<Long, String[]> edge : ends.entrySet()) {
                    if (!edge.getValue()[0].equals(current.getKey())) {
                        continue;
                    }
                    String next = edge.getValue()[1];
                    double cost = current.getValue() + minutes.get(edge.getKey());
                    if (cost < best.getOrDefault(next, Double.MAX_VALUE)) {
                        best.put(next, cost);
                        via.put(next, edge.getKey());
                        queue.add(Map.entry(next, cost));
                    }
                }
            }
            // targets: a node, or the middle of a road reached from either end
            String endNode;
            RoutedSegment last = null;
            if (to.node() != null) {
                endNode = to.node();
            } else {
                long forward = to.forward();
                long backward = to.backward();
                double overForward = best.get(ends.get(forward)[0]) + minutes.get(forward) / 2;
                double overBackward = best.get(ends.get(backward)[0]) + minutes.get(backward) / 2;
                long entered = overForward <= overBackward ? forward : backward;
                endNode = ends.get(entered)[0];
                last = half(entered, 0, 0.5);
            }
            List<RoutedSegment> path = new ArrayList<>();
            String node = endNode;
            while (via.containsKey(node)) {
                long id = via.get(node);
                path.addFirst(full(id));
                node = ends.get(id)[0];
            }
            if (first.containsKey(node)) {
                path.addFirst(first.get(node));
            }
            if (last != null) {
                path.add(last);
            }
            return path;
        }
    }
}
