package com.nfv.network.logic;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.exceptions.PointOutOfBoundsException;
import com.graphhopper.util.shapes.GHPoint;
import com.nfv.common.errorhandling.TechnicalException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Routes on the road network of a region, result in road segment ids, see Docs/network.md#routing-details. */
@Service
@RequiredArgsConstructor
public class Routing {
    static final double MAX_SNAP_DISTANCE_METERS = 500;
    private static final List<String> SNAP_PREVENTIONS = List.of("tunnel", "bridge", "ferry");

    private final Graphs graphs;
    private final SegmentWriter segmentWriter;

    /**
     * Route through the points: first and last are stops, everything in between via points.
     *
     * @throws NoRouteException            point outside every region, too far from a road or not connected
     * @throws RoutingUnavailableException no region has a graph yet
     */
    public RouteResult route(List<RoutePoint> points, RouteOptions options) {
        validate(points);
        String region = region(points);
        return graphs.withGraph(region, graph -> {
            GHRequest request = request(graph, points, options);
            routeRequest(request);
            return result(region, graph, points, response(graph, request).getBest());
        });
    }

    /**
     * Fastest route and alternatives between two stops with GraphHopper {@code alternative_route}. GraphHopper 11.1
     * does not support pass_through there, see Docs/recommendations.md#generatoren. Between two stops the request is
     * the same as for {@link #route}, so the fastest alternative can be planned again.
     *
     * @return at least one route, the first is the fastest
     * @throws NoRouteException            point outside every region, too far from a road or not connected
     * @throws RoutingUnavailableException no region has a graph yet
     */
    public List<RouteResult> alternatives(RoutePoint from, RoutePoint to, RouteOptions options,
                                          AlternativeOptions alternatives) {
        List<RoutePoint> points = List.of(from, to);
        validate(points);
        String region = region(points);
        return graphs.withGraph(region, graph -> {
            GHRequest request = request(graph, points, options);
            request.setAlgorithm(Parameters.Algorithms.ALT_ROUTE);
            request.putHint(Parameters.Algorithms.AltRoute.MAX_PATHS, alternatives.maxPaths());
            request.putHint(Parameters.Algorithms.AltRoute.MAX_WEIGHT, alternatives.maxWeightFactor());
            request.putHint(Parameters.Algorithms.AltRoute.MAX_SHARE, alternatives.maxShareFactor());
            return response(graph, request).getAll().stream()
                    .map(path -> result(region, graph, points, path))
                    .toList();
        });
    }

    private String region(List<RoutePoint> points) {
        return graphs.regionContaining(points.stream().map(RoutePoint::point).toList())
                .orElseThrow(() -> graphs.anyReady()
                        ? new NoRouteException("points outside of all regions")
                        : new RoutingUnavailableException("no region ready yet"));
    }

    private static GHResponse response(Graphs.LoadedGraph graph, GHRequest request) {
        GHResponse response = graph.hopper().route(request);
        if (response.hasErrors()) {
            throw error(response.getErrors().getFirst());
        }
        return response;
    }

    private static RouteResult result(String region, Graphs.LoadedGraph graph, List<RoutePoint> points,
                                      ResponsePath path) {
        List<Interval> intervals = intervals(path);
        List<RoutedSegment> segments = segments(graph, path, intervals);
        return new RouteResult(region, graph.versionId(), path.getDistance(), Duration.ofMillis(path.getTime()),
                line(path.getPoints()), segments, snappedPoints(points, path, intervals, graph));
    }

    /** Request with points, details and the restrictions of the options, shared by route and alternatives. */
    private GHRequest request(Graphs.LoadedGraph graph, List<RoutePoint> points, RouteOptions options) {
        GHRequest request = new GHRequest(points.stream()
                .map(point -> new GHPoint(point.point().lat(), point.point().lon())).toList())
                .setProfile(NetworkGraphHopper.PROFILE)
                .setPathDetails(List.of(Parameters.Details.EDGE_KEY, Parameters.Details.DISTANCE,
                        Parameters.Details.TIME))
                .setPointHints(points.stream().map(point -> point.roadHint() == null ? "" : point.roadHint())
                        .toList())
                .setSnapPreventions(SNAP_PREVENTIONS);
        if (!options.blockedSegmentIds().isEmpty()) {
            request.putHint(NetworkGraphHopper.BLOCKED_EDGE_KEYS,
                    segmentWriter.edgeKeys(graph.versionId(), options.blockedSegmentIds()));
        }
        if (!options.delayedSegments().isEmpty()) {
            request.putHint(NetworkGraphHopper.DELAYED_EDGE_KEYS,
                    segmentWriter.edgeDelays(graph.versionId(), options.delayedSegments()));
        }
        return request;
    }

    /**
     * No u-turn at via points, only for {@link #route}. No curbsides at the stops: {@code alternative_route} cannot
     * keep them, and a suggestion must be plannable again, see Docs/network.md#routing-details.
     */
    private static void routeRequest(GHRequest request) {
        request.putHint(Parameters.Routing.PASS_THROUGH, true);
    }

    /** One driven edge: path point indices, edge key, distance and time summed from the path details. */
    private record Interval(int first, int last, int edgeKey, double distance, long time) {
    }

    private static List<Interval> intervals(ResponsePath path) {
        List<PathDetail> edgeKeys = path.getPathDetails().get(Parameters.Details.EDGE_KEY);
        double[] distances = new double[edgeKeys.size()];
        long[] times = new long[edgeKeys.size()];
        sum(edgeKeys, path.getPathDetails().get(Parameters.Details.DISTANCE),
                (i, detail) -> distances[i] += ((Number) detail.getValue()).doubleValue());
        sum(edgeKeys, path.getPathDetails().get(Parameters.Details.TIME),
                (i, detail) -> times[i] += ((Number) detail.getValue()).longValue());
        List<Interval> intervals = new ArrayList<>(edgeKeys.size());
        for (int i = 0; i < edgeKeys.size(); i++) {
            PathDetail detail = edgeKeys.get(i);
            intervals.add(new Interval(detail.getFirst(), detail.getLast(), ((Number) detail.getValue()).intValue(),
                    distances[i], times[i]));
        }
        return intervals;
    }

    private interface Adder {
        void add(int interval, PathDetail detail);
    }

    /** Adds every detail to the edge key interval it starts in, both lists are ordered along the path. */
    private static void sum(List<PathDetail> edgeKeys, List<PathDetail> details, Adder adder) {
        int interval = 0;
        for (PathDetail detail : details) {
            while (interval < edgeKeys.size() - 1 && detail.getFirst() >= edgeKeys.get(interval).getLast()) {
                interval++;
            }
            adder.add(interval, detail);
        }
    }

    private static List<RoutedSegment> segments(Graphs.LoadedGraph graph, ResponsePath path,
                                                List<Interval> intervals) {
        PointList waypoints = path.getWaypoints();
        List<RoutedSegment> segments = new ArrayList<>(intervals.size());
        for (int i = 0; i < intervals.size(); i++) {
            Interval interval = intervals.get(i);
            long segmentId = graph.segmentId(interval.edgeKey());
            if (segmentId == 0) {
                continue; // zero-length edge at a barrier
            }
            PointList geometry = graph.hopper().getBaseGraph().getEdgeIteratorStateForKey(interval.edgeKey())
                    .fetchWayGeometry(FetchMode.ALL);
            double from = i == 0 ? fraction(geometry, waypoints.getLat(0), waypoints.getLon(0)) : 0;
            int last = waypoints.size() - 1;
            double to = i == intervals.size() - 1
                    ? fraction(geometry, waypoints.getLat(last), waypoints.getLon(last)) : 1;
            segments.add(new RoutedSegment(segmentId, from, Math.max(from, to), interval.distance(),
                    Duration.ofMillis(interval.time())));
        }
        return segments;
    }

    private static List<SnappedPoint> snappedPoints(List<RoutePoint> points, ResponsePath path,
                                                    List<Interval> intervals, Graphs.LoadedGraph graph) {
        PointList waypoints = path.getWaypoints();
        PointList pathPoints = path.getPoints();
        List<SnappedPoint> snapped = new ArrayList<>(waypoints.size());
        int searchFrom = 0;
        for (int i = 0; i < waypoints.size(); i++) {
            GeoPoint requested = points.get(i).point();
            double distance = DistanceCalcEarth.DIST_EARTH.calcDist(requested.lat(), requested.lon(),
                    waypoints.getLat(i), waypoints.getLon(i));
            if (distance > MAX_SNAP_DISTANCE_METERS) {
                throw new NoRouteException(String.format(Locale.ROOT, "point %d is %.0f m from the nearest road",
                        i, distance));
            }
            int index = i == waypoints.size() - 1 ? pathPoints.size() - 1
                    : indexOf(pathPoints, waypoints.getLat(i), waypoints.getLon(i), searchFrom);
            searchFrom = index;
            snapped.add(new SnappedPoint(new GeoPoint(waypoints.getLat(i), waypoints.getLon(i)), distance,
                    segmentPosition(intervals, index, graph)));
        }
        return snapped;
    }

    /** Index into the routed segments of the edge containing the path point, zero-length edges have none. */
    private static int segmentPosition(List<Interval> intervals, int pointIndex, Graphs.LoadedGraph graph) {
        int position = -1;
        for (Interval interval : intervals) {
            if (graph.segmentId(interval.edgeKey()) != 0) {
                position++;
            }
            if (pointIndex < interval.last()) {
                break;
            }
        }
        return Math.max(position, 0);
    }

    private static int indexOf(PointList points, double lat, double lon, int from) {
        for (int i = from; i < points.size(); i++) {
            if (Math.abs(points.getLat(i) - lat) < 1e-7 && Math.abs(points.getLon(i) - lon) < 1e-7) {
                return i;
            }
        }
        return from;
    }

    /** Position of a point along a line, 0 at its start and 1 at its end. */
    static double fraction(PointList line, double lat, double lon) {
        double cosLat = Math.cos(Math.toRadians(lat));
        double total = 0;
        double best = Double.MAX_VALUE;
        double along = 0;
        for (int i = 0; i + 1 < line.size(); i++) {
            double ax = line.getLon(i) * cosLat;
            double ay = line.getLat(i);
            double dx = line.getLon(i + 1) * cosLat - ax;
            double dy = line.getLat(i + 1) - ay;
            double length = Math.hypot(dx, dy);
            double t = length == 0 ? 0
                    : Math.clamp(((lon * cosLat - ax) * dx + (lat - ay) * dy) / (length * length), 0, 1);
            double distance = Math.hypot(ax + t * dx - lon * cosLat, ay + t * dy - lat);
            if (distance < best) {
                best = distance;
                along = total + t * length;
            }
            total += length;
        }
        return total == 0 ? 0 : Math.clamp(along / total, 0, 1);
    }

    private static GeoLine line(PointList points) {
        List<GeoPoint> line = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            line.add(new GeoPoint(points.getLat(i), points.getLon(i)));
        }
        return new GeoLine(line);
    }

    private static RuntimeException error(Throwable error) {
        if (error instanceof PointNotFoundException || error instanceof PointOutOfBoundsException
                || error instanceof ConnectionNotFoundException) {
            return new NoRouteException(error.getMessage());
        }
        return new TechnicalException("routing failed: " + error.getMessage());
    }

    private static void validate(List<RoutePoint> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("a route needs at least two points");
        }
        for (int i = 0; i < points.size(); i++) {
            RoutePoint.Kind expected = i == 0 || i == points.size() - 1 ? RoutePoint.Kind.STOP : RoutePoint.Kind.VIA;
            if (points.get(i).kind() != expected) {
                throw new IllegalArgumentException("point " + i + " must be " + expected
                        + ", stops are only first and last");
            }
        }
    }
}