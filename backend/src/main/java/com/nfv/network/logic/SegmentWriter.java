package com.nfv.network.logic;

import com.carrotsearch.hppc.IntDoubleHashMap;
import com.carrotsearch.hppc.IntHashSet;
import com.graphhopper.GraphHopper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Writes the segments of a graph into {@code road_segment} (upsert on the business key) and its {@code edge_mapping},
 * see Docs/network.md#import-and-update.
 */
@Component
@RequiredArgsConstructor
class SegmentWriter {
    private static final int BATCH = 1_000;
    private static final String KEY = "osm_way_id, from_lat, from_lon, to_lat, to_lon, geometry_hash";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** Returns the number of segments of the version. */
    @Transactional
    public int write(String region, long versionId, GraphHopper hopper) {
        jdbc.execute("""
                CREATE TEMP TABLE road_segment_staging (
                    edge_key INTEGER, osm_way_id BIGINT, from_lat INTEGER, from_lon INTEGER, to_lat INTEGER,
                    to_lon INTEGER, geometry_hash INTEGER, ref VARCHAR(255), name VARCHAR(255),
                    road_class VARCHAR(32), bridge BOOLEAN, tunnel BOOLEAN, link BOOLEAN, length_m DOUBLE PRECISION,
                    wkt TEXT
                ) ON COMMIT DROP""");
        List<Object[]> batch = new ArrayList<>(BATCH);
        GraphSegments.forEach(hopper, row -> {
            batch.add(new Object[]{row.edgeKey(), row.osmWayId(), row.fromLat(), row.fromLon(), row.toLat(),
                    row.toLon(), row.geometryHash(), row.ref(), row.name(), row.roadClass(), row.bridge(),
                    row.tunnel(), row.link(), row.lengthMeters(), row.wkt()});
            if (batch.size() == BATCH) {
                insertStaging(batch);
                batch.clear();
            }
        });
        insertStaging(batch);

        // DISTINCT ON: identical duplicate edges become one segment
        int segments = jdbc.update("""
                INSERT INTO road_segment (region, %1$s, ref, name, road_class, bridge, tunnel, link, length_m, geometry,
                                          first_seen_version_id, last_seen_version_id)
                SELECT DISTINCT ON (%1$s) ?, %1$s, ref, name, road_class, bridge, tunnel, link, length_m,
                       ST_GeomFromText(wkt, 4326), ?, ?
                FROM road_segment_staging
                ON CONFLICT (%1$s) DO UPDATE SET
                    ref = EXCLUDED.ref, name = EXCLUDED.name, road_class = EXCLUDED.road_class,
                    bridge = EXCLUDED.bridge, tunnel = EXCLUDED.tunnel, link = EXCLUDED.link,
                    length_m = EXCLUDED.length_m,
                    geometry = EXCLUDED.geometry, last_seen_version_id = EXCLUDED.last_seen_version_id,
                    retired_at = NULL""".formatted(KEY), region, versionId, versionId);
        jdbc.update("""
                INSERT INTO edge_mapping (extract_version_id, edge_key, road_segment_id)
                SELECT ?, s.edge_key, r.id
                FROM road_segment_staging s
                JOIN road_segment r ON r.osm_way_id = s.osm_way_id AND r.from_lat = s.from_lat
                    AND r.from_lon = s.from_lon AND r.to_lat = s.to_lat AND r.to_lon = s.to_lon
                    AND r.geometry_hash = s.geometry_hash""", versionId);
        jdbc.update("""
                UPDATE road_segment SET retired_at = ?
                WHERE region = ? AND last_seen_version_id <> ? AND retired_at IS NULL""",
                Timestamp.from(clock.instant()), region, versionId);
        return segments;
    }

    /** Whether any segment of the region is marked as a link, false for segments written before V37. */
    @Transactional(readOnly = true)
    public boolean hasLinks(String region) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM road_segment WHERE region = ? AND link)", Boolean.class, region));
    }

    /** {@code edge_key} to {@code road_segment.id} of a version, 0 for edges without segment. */
    @Transactional(readOnly = true)
    public long[] mapping(long versionId, int edgeKeys) {
        long[] mapping = new long[edgeKeys];
        jdbc.query("SELECT edge_key, road_segment_id FROM edge_mapping WHERE extract_version_id = ?",
                (RowCallbackHandler) rs -> mapping[rs.getInt(1)] = rs.getLong(2), versionId);
        return mapping;
    }

    /** All edge keys of the segments in a version, e.g. to block them for routing. */
    @Transactional(readOnly = true)
    public IntHashSet edgeKeys(long versionId, Collection<Long> segmentIds) {
        IntHashSet keys = new IntHashSet();
        if (!segmentIds.isEmpty()) {
            queryEdgeKeys(versionId, segmentIds, rs -> keys.add(rs.getInt(1)));
        }
        return keys;
    }

    /**
     * Extra seconds per edge key of the delayed segments in a version. A segment is one direction of an edge, several
     * edge keys only for identical duplicate edges: a passage uses one of them, so each gets the full delay.
     */
    @Transactional(readOnly = true)
    public IntDoubleHashMap edgeDelays(long versionId, Map<Long, Duration> delays) {
        IntDoubleHashMap seconds = new IntDoubleHashMap();
        if (!delays.isEmpty()) {
            queryEdgeKeys(versionId, delays.keySet(),
                    rs -> seconds.put(rs.getInt(1), delays.get(rs.getLong(2)).toMillis() / 1000.0));
        }
        return seconds;
    }

    private void queryEdgeKeys(long versionId, Collection<Long> segmentIds, RowCallbackHandler handler) {
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("SELECT edge_key, road_segment_id"
                    + " FROM edge_mapping WHERE extract_version_id = ? AND road_segment_id = ANY(?)");
            statement.setLong(1, versionId);
            statement.setArray(2, connection.createArrayOf("bigint", segmentIds.toArray()));
            return statement;
        }, handler);
    }

    /** Removes the mapping of a version that is no longer in use. */
    @Transactional
    public void deleteMapping(long versionId) {
        jdbc.update("DELETE FROM edge_mapping WHERE extract_version_id = ?", versionId);
    }

    private void insertStaging(List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO road_segment_staging (edge_key, %s, ref, name, road_class, bridge, tunnel, link,
                                                      length_m, wkt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".formatted(KEY), rows);
        }
    }
}