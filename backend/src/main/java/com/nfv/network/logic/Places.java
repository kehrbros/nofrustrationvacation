package com.nfv.network.logic;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;

/** Places along a route for other modules, see Docs/network.md#orte. */
@Service
@RequiredArgsConstructor
public class Places {
    private static final double METERS_PER_DEGREE = 111_320;

    private final JdbcTemplate jdbc;

    /**
     * A place and the segment of the path closest to it.
     *
     * @param pathIndex       position of that segment in the path
     * @param segmentFraction where on the whole segment the place lies, from 0 (its start) to 1 (its end)
     * @param offRouteMeters  straight line from the segment to the place
     */
    public record PlaceNearRoute(String name, PlaceKind kind, GeoPoint point, int pathIndex, double segmentFraction,
                                 double offRouteMeters) {
    }

    /**
     * Places close to the segments of a path. Each place appears once, at its closest segment; if the path passes it
     * twice (there and back on one road), at the first passage. Distances are compared in whole meters for that,
     * the two directions of a road differ by less.
     *
     * @param segmentIds the path in driving order
     */
    @Transactional(readOnly = true)
    public List<PlaceNearRoute> near(List<Long> segmentIds, int maxDistanceMeters) {
        if (segmentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(connection -> {
            // first ST_DWithin in degrees uses the index on place, the second one measures
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT ON (p.id) p.name, p.kind, ST_Y(p.geometry), ST_X(p.geometry), r.ord - 1,
                           ST_LineLocatePoint(s.geometry, p.geometry),
                           ST_Distance(s.geometry::geography, p.geometry::geography)
                    FROM unnest(?) WITH ORDINALITY AS r(id, ord)
                    JOIN road_segment s ON s.id = r.id
                    JOIN place p ON ST_DWithin(p.geometry, s.geometry,
                                       ? / GREATEST(COS(RADIANS(ST_Y(ST_StartPoint(s.geometry)))), 0.1))
                                AND ST_DWithin(p.geometry::geography, s.geometry::geography, ?)
                    ORDER BY p.id, ROUND(ST_Distance(s.geometry::geography, p.geometry::geography)), r.ord""");
            statement.setArray(1, connection.createArrayOf("bigint", segmentIds.toArray()));
            statement.setDouble(2, maxDistanceMeters / METERS_PER_DEGREE);
            statement.setDouble(3, maxDistanceMeters);
            return statement;
        }, (rs, row) -> new PlaceNearRoute(rs.getString(1), PlaceKind.valueOf(rs.getString(2)),
                new GeoPoint(rs.getDouble(3), rs.getDouble(4)), rs.getInt(5), rs.getDouble(6), rs.getDouble(7)));
    }

    /**
     * A place around a point.
     *
     * @param distanceMeters straight line from the point
     */
    public record PlaceAround(String name, PlaceKind kind, GeoPoint point, double distanceMeters) {
    }

    /** Places within {@code maxDistanceMeters} of a point, closest first, at most {@code limit}. */
    @Transactional(readOnly = true)
    public List<PlaceAround> around(GeoPoint center, int maxDistanceMeters, int limit) {
        double cos = Math.max(Math.cos(Math.toRadians(center.lat())), 0.1);
        return jdbc.query("""
                        SELECT p.name, p.kind, ST_Y(p.geometry), ST_X(p.geometry),
                               ST_Distance(p.geometry::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS d
                        FROM place p
                        WHERE ST_DWithin(p.geometry, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?)
                          AND ST_DWithin(p.geometry::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                        ORDER BY d
                        LIMIT ?""",
                (rs, row) -> new PlaceAround(rs.getString(1), PlaceKind.valueOf(rs.getString(2)),
                        new GeoPoint(rs.getDouble(3), rs.getDouble(4)), rs.getDouble(5)),
                center.lon(), center.lat(), center.lon(), center.lat(), maxDistanceMeters / METERS_PER_DEGREE / cos,
                center.lon(), center.lat(), (double) maxDistanceMeters, limit);
    }
}
