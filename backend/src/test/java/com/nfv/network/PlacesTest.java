package com.nfv.network;

import com.nfv.network.logic.PlaceKind;
import com.nfv.network.logic.Places;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Places along segments on central Wellington, see Docs/network.md#orte. */
@SpringBootTest
class PlacesTest {
    private static final String REGION = "wellington";

    @Autowired
    private Places places;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private JdbcTemplate jdbc;

    private long versionId;
    /** A two-way segment over 100 m and its other direction. */
    private long segment;
    private long reverse;

    @BeforeEach
    void wellington() {
        if (regionImport.status(REGION).state() != RegionStatus.State.READY) {
            regionImport.importRegion(REGION);
        }
        versionId = regionImport.status(REGION).extractVersionId();
        List<long[]> pairs = jdbc.query("""
                SELECT s.id, r.id FROM road_segment s JOIN road_segment r ON r.osm_way_id = s.osm_way_id
                    AND r.from_lat = s.to_lat AND r.from_lon = s.to_lon AND r.to_lat = s.from_lat
                    AND r.to_lon = s.from_lon AND r.id <> s.id
                WHERE s.retired_at IS NULL AND r.retired_at IS NULL AND s.length_m > 100
                ORDER BY s.id LIMIT 1""", (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)});
        segment = pairs.getFirst()[0];
        reverse = pairs.getFirst()[1];
    }

    @AfterEach
    void removePlaces() {
        jdbc.update("DELETE FROM place WHERE region = ?", REGION);
    }

    @Test
    void placeIsFoundAtItsClosestSegmentWithPositionAndDistance() {
        // about 55 m north of the point a quarter along the segment
        insert("Town", "TOWN", 0.25, 0.0005);
        List<Long> path = List.of(farSegment(), segment);

        List<Places.PlaceNearRoute> found = places.near(path, 200);

        assertThat(found).singleElement().satisfies(place -> {
            assertThat(place.name()).isEqualTo("Town");
            assertThat(place.kind()).isEqualTo(PlaceKind.TOWN);
            assertThat(place.pathIndex()).isEqualTo(1);
            assertThat(place.segmentFraction()).isBetween(0.0, 1.0);
            assertThat(place.offRouteMeters()).isCloseTo(40, within(20.0));
        });
    }

    @Test
    void placeBeyondTheDistanceIsNotFound() {
        insert("Town", "TOWN", 0.5, 0.01);

        assertThat(places.near(List.of(segment), 200)).isEmpty();
        assertThat(places.near(List.of(segment), 2000)).hasSize(1);
    }

    @Test
    void thereAndBackOnOneRoadCountsTheFirstPassage() {
        insert("Town", "TOWN", 0.5, 0);

        assertThat(places.near(List.of(segment, reverse), 200)).singleElement()
                .satisfies(place -> assertThat(place.pathIndex()).isZero());
        assertThat(places.near(List.of(reverse, segment), 200)).singleElement()
                .satisfies(place -> assertThat(place.pathIndex()).isZero());
    }

    @Test
    void emptyPathFindsNothing() {
        insert("Town", "TOWN", 0.5, 0);

        assertThat(places.near(List.of(), 200)).isEmpty();
    }

    /** @param north degrees of latitude away from the point on the segment */
    private void insert(String name, String kind, double fraction, double north) {
        jdbc.update("""
                INSERT INTO place (region, extract_version_id, name, kind, geometry)
                SELECT ?, ?, ?, ?, ST_Translate(ST_LineInterpolatePoint(geometry, ?), 0, ?)
                FROM road_segment WHERE id = ?""", REGION, versionId, name, kind, fraction, north, segment);
    }

    /** A segment more than 2 km from the one under test. */
    private long farSegment() {
        Long far = jdbc.queryForObject("""
                SELECT MIN(o.id) FROM road_segment o, road_segment s
                WHERE s.id = ? AND NOT ST_DWithin(o.geometry::geography, s.geometry::geography, 2000)""",
                Long.class, segment);
        return far == null ? segment : far;
    }
}
