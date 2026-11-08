package com.nfv.network;

import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Segment details on central Wellington, see Docs/network.md#schnittstellen-networklogic. */
@SpringBootTest
class SegmentQueriesTest {
    @Autowired
    private SegmentQueries segmentQueries;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void wellington() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.loadOrImport("wellington");
        }
    }

    @Test
    void twoWayRoadHasReverseSegment() {
        // a two-way segment: some other segment of the same way runs from its end to its start
        Long id = jdbc.queryForObject("""
                SELECT MIN(s.id) FROM road_segment s JOIN road_segment r ON r.osm_way_id = s.osm_way_id
                    AND r.from_lat = s.to_lat AND r.from_lon = s.to_lon AND r.to_lat = s.from_lat
                    AND r.to_lon = s.from_lon AND r.id <> s.id
                WHERE s.retired_at IS NULL AND r.retired_at IS NULL""", Long.class);

        SegmentInfo info = segmentQueries.segments(List.of(id)).get(id);
        Long reverseId = info.reverseId();

        assertThat(reverseId).isNotNull();
        assertThat(segmentQueries.segments(List.of(reverseId)).get(reverseId).reverseId()).isEqualTo(id);
    }

    @Test
    void tunnelHasRefAndMidpointOnTheSegment() {
        Long id = jdbc.queryForObject(
                "SELECT MIN(id) FROM road_segment WHERE tunnel AND ref = 'SH 1' AND retired_at IS NULL", Long.class);

        SegmentInfo info = segmentQueries.segments(List.of(id)).get(id);

        assertThat(info.ref()).isEqualTo("SH 1");
        assertThat(info.lengthMeters()).isPositive();
        Double distance = jdbc.queryForObject("""
                SELECT ST_Distance(geometry::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                FROM road_segment WHERE id = ?""", Double.class,
                info.midpoint().lon(), info.midpoint().lat(), id);
        assertThat(distance).isCloseTo(0, within(0.5));
    }

    @Test
    void unknownIdsAreMissing() {
        Map<Long, SegmentInfo> segments = segmentQueries.segments(List.of(-1L));

        assertThat(segments).isEmpty();
    }
}