package com.nfv.network.logic;

import com.graphhopper.matching.EdgeMatch;
import com.graphhopper.matching.MapMatching;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.matching.Observation;
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PMap;
import com.graphhopper.util.shapes.GHPoint;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Locates traffic events on road segments, see Docs/network.md#interfaces-networklogic. Unlike snapping stops,
 * tunnels and bridges are included (an event inside the Terrace Tunnel is on the tunnel); the road number keeps
 * events off roads above or below.
 * <ul>
 *     <li>point: segments within a radius, with the road number if given</li>
 *     <li>line: map matching on the graph of the region, both directions unless a bearing is given</li>
 * </ul>
 * With a bearing only segments heading that way (± {@value #BEARING_TOLERANCE}°, start to end) are kept.
 */
@Service
@RequiredArgsConstructor
public class SegmentMatching {
    static final double BEARING_TOLERANCE = 60;
    /** GPS accuracy assumed by map matching; the lines of sources lie close to the road. */
    private static final double MEASUREMENT_ERROR_METERS = 20;
    private static final double METERS_PER_DEGREE = 111_320;
    private static final Logger LOG = LoggerFactory.getLogger(SegmentMatching.class);
    private static final String COLUMNS = """
            SELECT s.id, s.ref,
                   degrees(ST_Azimuth(ST_StartPoint(s.geometry)::geography, ST_EndPoint(s.geometry)::geography)),
                   (SELECT MIN(r.id) FROM road_segment r
                    WHERE r.osm_way_id = s.osm_way_id AND r.from_lat = s.to_lat AND r.from_lon = s.to_lon
                      AND r.to_lat = s.from_lat AND r.to_lon = s.from_lon AND r.retired_at IS NULL AND r.id <> s.id),
                   s.link
            FROM road_segment s
            """;

    private final Graphs graphs;
    private final JdbcTemplate jdbc;
    private final RefNormalizer refNormalizer;

    private record Candidate(long id, @Nullable String ref, double bearing, @Nullable Long reverseId, boolean link) {
    }

    /**
     * Segments within {@code radiusMeters} of the point.
     *
     * @param roadRef road number as the source names it, only segments with that number; {@code null}: all
     * @param bearing direction of travel in degrees (0 = north, 90 = east), {@code null}: both directions
     * @throws RoutingUnavailableException the region has no network yet
     */
    // callers catch RoutingUnavailableException and go on in their transaction
    @Transactional(readOnly = true, noRollbackFor = RoutingUnavailableException.class)
    public Set<Long> matchPoint(String region, GeoPoint point, double radiusMeters, @Nullable String roadRef,
                                @Nullable Double bearing) {
        return matchPoint(region, point, radiusMeters, roadRef, bearing, false);
    }

    /**
     * As {@link #matchPoint(String, GeoPoint, double, String, Double)}.
     *
     * @param linkOnly the event concerns a ramp or link: only those, whatever their road number (they mostly have none)
     */
    @Transactional(readOnly = true, noRollbackFor = RoutingUnavailableException.class)
    public Set<Long> matchPoint(String region, GeoPoint point, double radiusMeters, @Nullable String roadRef,
                                @Nullable Double bearing, boolean linkOnly) {
        requireNetwork(region);
        double degrees = radiusMeters / METERS_PER_DEGREE / Math.max(Math.cos(Math.toRadians(point.lat())), 0.1);
        List<Candidate> candidates = jdbc.query(COLUMNS + """
                        WHERE s.region = ? AND s.retired_at IS NULL
                          AND ST_DWithin(s.geometry, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?)
                          AND ST_DWithin(s.geometry::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                        ORDER BY s.id""",
                (rs, row) -> candidate(rs),
                region, point.lon(), point.lat(), degrees, point.lon(), point.lat(), radiusMeters);
        return filter(region, candidates, roadRef, bearing, linkOnly);
    }

    /**
     * Segments along the line by map matching. Points outside the network of the region are left out, so a line
     * reaching beyond it matches the part inside.
     *
     * @param roadRef road number as the source names it, only segments with that number; {@code null}: all
     * @param bearing direction of travel in degrees, {@code null}: both directions, whatever the order of the points
     * @throws RoutingUnavailableException the region has no network yet
     */
    @Transactional(readOnly = true, noRollbackFor = RoutingUnavailableException.class)
    public Set<Long> matchLine(String region, GeoLine line, @Nullable String roadRef, @Nullable Double bearing) {
        return matchLine(region, line, roadRef, bearing, false);
    }

    /**
     * As {@link #matchLine(String, GeoLine, String, Double)}.
     *
     * @param linkOnly the event concerns a ramp or link: only those, whatever their road number (they mostly have none)
     */
    @Transactional(readOnly = true, noRollbackFor = RoutingUnavailableException.class)
    public Set<Long> matchLine(String region, GeoLine line, @Nullable String roadRef, @Nullable Double bearing,
                               boolean linkOnly) {
        Set<Long> matched = graphs.withGraph(region, graph -> {
            List<Observation> observations = line.points().stream()
                    .filter(p -> graph.bounds().contains(p.lat(), p.lon()))
                    .map(p -> new Observation(new GHPoint(p.lat(), p.lon())))
                    .toList();
            Set<Long> ids = new LinkedHashSet<>();
            if (observations.size() < 2) {
                return ids;
            }
            // The order of the points says nothing about the direction of travel: match both ways, so divided roads
            // (one one-way way per carriageway) are found in both directions; the bearing (if any) picks one
            ids.addAll(mapMatch(region, graph, observations));
            ids.addAll(mapMatch(region, graph, observations.reversed()));
            return ids;
        });
        // Two-way roads: the opposite direction of each matched segment
        List<Candidate> candidates = new ArrayList<>(byIds(matched));
        candidates.addAll(byIds(candidates.stream().map(Candidate::reverseId)
                .filter(id -> id != null && !matched.contains(id)).distinct().toList()));
        return filter(region, candidates, roadRef, bearing, linkOnly);
    }

    private static Set<Long> mapMatch(String region, Graphs.LoadedGraph graph, List<Observation> observations) {
        Set<Long> ids = new LinkedHashSet<>();
        MapMatching mapMatching = MapMatching.fromGraphHopper(graph.hopper(),
                new PMap().putObject("profile", NetworkGraphHopper.PROFILE));
        mapMatching.setMeasurementErrorSigma(MEASUREMENT_ERROR_METERS);
        MatchResult result;
        try {
            result = mapMatching.match(observations);
        } catch (IllegalArgumentException e) {
            LOG.debug("Map matching failed in {}: {}", region, e.getMessage());
            return ids;
        }
        for (EdgeMatch match : result.getEdgeMatches()) {
            EdgeIteratorState edge = match.getEdgeState();
            int edgeKey = edge instanceof VirtualEdgeIteratorState virtual
                    ? virtual.getOriginalEdgeKey() : edge.getEdgeKey();
            long segmentId = graph.segmentId(edgeKey);
            if (segmentId != 0) {
                ids.add(segmentId);
            }
        }
        return ids;
    }

    private List<Candidate> byIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(COLUMNS
                    + " WHERE s.id = ANY(?) AND s.retired_at IS NULL ORDER BY s.id");
            statement.setArray(1, connection.createArrayOf("bigint", ids.toArray()));
            return statement;
        }, (rs, row) -> candidate(rs));
    }

    private Set<Long> filter(String region, List<Candidate> candidates, @Nullable String roadRef,
                             @Nullable Double bearing, boolean linkOnly) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (linkOnly ? !candidate.link()
                    : roadRef != null && !refNormalizer.matches(region, roadRef, candidate.ref())) {
                continue;
            }
            if (bearing != null && angle(bearing, candidate.bearing()) > BEARING_TOLERANCE) {
                continue;
            }
            ids.add(candidate.id());
        }
        return ids;
    }

    /** Smallest angle between two bearings, 0–180°. */
    static double angle(double a, double b) {
        double difference = Math.abs(a - b) % 360;
        return difference > 180 ? 360 - difference : difference;
    }

    private void requireNetwork(String region) {
        if (graphs.status(region).extractVersionId() == null) {
            throw new RoutingUnavailableException("region " + region + " has no network yet");
        }
    }

    /** A row of {@link #COLUMNS}. */
    private static Candidate candidate(ResultSet rs) throws SQLException {
        return new Candidate(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getObject(4, Long.class),
                rs.getBoolean(5));
    }
}
