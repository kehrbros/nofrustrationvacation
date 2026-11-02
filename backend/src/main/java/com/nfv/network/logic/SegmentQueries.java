package com.nfv.network.logic;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Details of road segments for other modules, see Docs/network.md#schnittstellen-networklogic. */
@Service
@RequiredArgsConstructor
public class SegmentQueries {
    private final JdbcTemplate jdbc;

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
}