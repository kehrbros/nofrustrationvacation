package com.nfv.network.logic;

import com.nfv.network.config.NetworkProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Details of road segments for other modules, see Docs/network.md#interfaces-networklogic. */
@Service
@RequiredArgsConstructor
public class SegmentQueries {
    private final JdbcTemplate jdbc;
    private final NetworkProperties properties;

    /**
     * Time zone of the region a segment lies in.
     *
     * @throws IllegalArgumentException unknown segment
     */
    @Transactional(readOnly = true)
    public ZoneId zone(long segmentId) {
        String region = jdbc.query("SELECT region FROM road_segment WHERE id = ?",
                        (rs, row) -> rs.getString(1), segmentId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown segment " + segmentId));
        NetworkProperties.Region config = properties.regions().get(region);
        if (config == null) {
            throw new IllegalArgumentException("segment " + segmentId + " lies in unknown region " + region);
        }
        return config.zone();
    }

    /** Details of the segments by id, unknown ids are missing. */
    @Transactional(readOnly = true)
    public Map<Long, SegmentInfo> segments(Collection<Long> segmentIds) {
        Map<Long, SegmentInfo> segments = new HashMap<>();
        if (segmentIds.isEmpty()) {
            return segments;
        }
        // reverse: same way and edge, start and end swapped; identical duplicates share a segment, take any
        List<SegmentInfo> rows = jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT s.id, s.ref, s.name, s.length_m,
                           ST_Y(ST_LineInterpolatePoint(s.geometry, 0.5)), ST_X(ST_LineInterpolatePoint(s.geometry, 0.5)),
                           (SELECT MIN(r.id) FROM road_segment r
                            WHERE r.osm_way_id = s.osm_way_id AND r.from_lat = s.to_lat AND r.from_lon = s.to_lon
                              AND r.to_lat = s.from_lat AND r.to_lon = s.from_lon AND r.retired_at IS NULL
                              AND r.id <> s.id)
                    FROM road_segment s WHERE s.id = ANY(?)""");
            statement.setArray(1, connection.createArrayOf("bigint", segmentIds.toArray()));
            return statement;
        }, (rs, row) -> new SegmentInfo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                new GeoPoint(rs.getDouble(5), rs.getDouble(6)), rs.getObject(7, Long.class)));
        rows.forEach(info -> segments.put(info.id(), info));
        return segments;
    }

    /**
     * Line over the parts in order, each cut to its fractions, e.g. a planned route for the map. Unknown segments are
     * left out, points repeated where two parts meet appear once.
     */
    @Transactional(readOnly = true)
    public GeoLine line(List<SectionPart> parts) {
        if (parts.isEmpty()) {
            return new GeoLine(List.of());
        }
        Long[] ids = parts.stream().map(SectionPart::segmentId).toArray(Long[]::new);
        Double[] from = parts.stream().map(SectionPart::fromFraction).toArray(Double[]::new);
        Double[] to = parts.stream().map(SectionPart::toFraction).toArray(Double[]::new);
        List<GeoPoint> rows = jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT ST_Y(d.geom), ST_X(d.geom)
                    FROM unnest(?, ?, ?) WITH ORDINALITY AS p(id, from_fraction, to_fraction, ord)
                    JOIN road_segment s ON s.id = p.id
                    CROSS JOIN LATERAL ST_DumpPoints(ST_LineSubstring(s.geometry, p.from_fraction, p.to_fraction)) AS d
                    ORDER BY p.ord, d.path[1]""");
            statement.setArray(1, connection.createArrayOf("bigint", ids));
            statement.setArray(2, connection.createArrayOf("float8", from));
            statement.setArray(3, connection.createArrayOf("float8", to));
            return statement;
        }, (rs, row) -> new GeoPoint(rs.getDouble(1), rs.getDouble(2)));
        List<GeoPoint> points = new ArrayList<>();
        for (GeoPoint point : rows) {
            if (points.isEmpty() || !points.getLast().equals(point)) {
                points.add(point);
            }
        }
        return new GeoLine(points);
    }

    /** Whether any of the segments lies in the region. */
    @Transactional(readOnly = true)
    public boolean anyInRegion(Collection<Long> segmentIds, String region) {
        if (segmentIds.isEmpty()) {
            return false;
        }
        Boolean any = jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM road_segment WHERE id = ANY(?) AND region = ?)");
            statement.setArray(1, connection.createArrayOf("bigint", segmentIds.toArray()));
            statement.setString(2, region);
            return statement;
        }, rs -> rs.next() && rs.getBoolean(1));
        return Boolean.TRUE.equals(any);
    }

    /**
     * Point on the parts nearest to {@code point}, e.g. a spot tapped next to a planned route.
     *
     * @param index          position of the part in {@code parts}
     * @param point          nearest point on that part, cut to its fractions
     * @param distanceMeters from {@code point} to it
     */
    public record ClosestPoint(int index, GeoPoint point, double distanceMeters) {
    }

    /** Nearest point on the parts (each cut to its fractions), empty without parts or known segments. */
    @Transactional(readOnly = true)
    public Optional<ClosestPoint> closest(List<SectionPart> parts, GeoPoint point) {
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        Long[] ids = parts.stream().map(SectionPart::segmentId).toArray(Long[]::new);
        Double[] from = parts.stream().map(SectionPart::fromFraction).toArray(Double[]::new);
        Double[] to = parts.stream().map(SectionPart::toFraction).toArray(Double[]::new);
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT c.ord, ST_Y(c.nearest), ST_X(c.nearest), ST_Distance(c.nearest::geography, c.tap::geography)
                    FROM (SELECT p.ord, t.tap, ST_ClosestPoint(ST_LineSubstring(s.geometry, p.from_fraction,
                                 p.to_fraction), t.tap) AS nearest
                          FROM unnest(?, ?, ?) WITH ORDINALITY AS p(id, from_fraction, to_fraction, ord)
                          JOIN road_segment s ON s.id = p.id
                          CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS tap) t) c
                    ORDER BY 4, 1
                    LIMIT 1""");
            statement.setArray(1, connection.createArrayOf("bigint", ids));
            statement.setArray(2, connection.createArrayOf("float8", from));
            statement.setArray(3, connection.createArrayOf("float8", to));
            statement.setDouble(4, point.lon());
            statement.setDouble(5, point.lat());
            return statement;
        }, (rs, row) -> new ClosestPoint(rs.getInt(1) - 1, new GeoPoint(rs.getDouble(2), rs.getDouble(3)),
                rs.getDouble(4))).stream().findFirst();
    }
}
