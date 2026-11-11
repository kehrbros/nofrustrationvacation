package com.nfv.network;

import com.nfv.network.logic.GeoLine;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.RefNormalizer;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.network.logic.RoutingUnavailableException;
import com.nfv.network.logic.SegmentMatching;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locating the NZTA sample events on the Wellington extract, see Docs/traffic-feed.md#test-environment:
 * 566003 is a point inside the Terrace Tunnel (SH 1), The Terrace runs above it; 566353 is a line along the urban
 * motorway that reaches beyond the extract.
 */
@SpringBootTest
class SegmentMatchingTest {
    private static final GeoPoint TERRACE_TUNNEL = new GeoPoint(-41.289262576382875, 174.77157677044664);
    private static final GeoLine MOTORWAY = new GeoLine(List.of(
            new GeoPoint(-41.28712008702765, 174.77176000000824),
            new GeoPoint(-41.275080087024214, 174.77409000000821),
            new GeoPoint(-41.25815008701931, 174.79397000000773),
            new GeoPoint(-41.24746008701616, 174.81373000000727),
            new GeoPoint(-41.24297008701483, 174.8131200000073)));

    @Autowired
    private SegmentMatching segmentMatching;
    @Autowired
    private RefNormalizer refNormalizer;
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
    void pointInTunnelMatchesTunnelOnlyWithRoadNumber() {
        Set<Long> withRef = segmentMatching.matchPoint("wellington", TERRACE_TUNNEL, 50, "01N", null);
        Set<Long> withoutRef = segmentMatching.matchPoint("wellington", TERRACE_TUNNEL, 50, null, null);

        assertThat(withRef).isNotEmpty();
        assertThat(strings("SELECT DISTINCT ref FROM road_segment WHERE id = ANY(?)", withRef)).containsOnly("SH 1");
        assertThat(strings("SELECT DISTINCT tunnel::text FROM road_segment WHERE id = ANY(?)", withRef))
                .containsOnly("true");
        assertThat(strings("SELECT DISTINCT name FROM road_segment WHERE id = ANY(?) AND name IS NOT NULL", withRef))
                .doesNotContain("The Terrace");
        // without the road number the street above is found as well
        assertThat(strings("SELECT DISTINCT name FROM road_segment WHERE id = ANY(?) AND name IS NOT NULL",
                withoutRef)).contains("The Terrace");
        assertThat(withoutRef).containsAll(withRef);
    }

    @Test
    void bearingKeepsOneDirection() {
        Set<Long> both = segmentMatching.matchPoint("wellington", TERRACE_TUNNEL, 50, "01N", null);
        Set<Long> northbound = segmentMatching.matchPoint("wellington", TERRACE_TUNNEL, 50, "01N", 0.0);
        Set<Long> southbound = segmentMatching.matchPoint("wellington", TERRACE_TUNNEL, 50, "01N", 180.0);

        assertThat(northbound).isNotEmpty();
        assertThat(both).containsAll(northbound).containsAll(southbound);
        assertThat(northbound).doesNotContainAnyElementsOf(southbound);
    }

    @Test
    void lineIsMapMatchedInBothDirections() {
        Set<Long> both = segmentMatching.matchLine("wellington", MOTORWAY, "01N", null);
        Set<Long> northbound = segmentMatching.matchLine("wellington", MOTORWAY, "01N", 0.0);

        assertThat(both).hasSizeGreaterThan(2);
        assertThat(strings("SELECT DISTINCT ref FROM road_segment WHERE id = ANY(?)", both)).containsOnly("SH 1");
        assertThat(northbound).isNotEmpty();
        // divided motorway: each carriageway is a one-way way of its own, both are found
        Set<Long> southbound = segmentMatching.matchLine("wellington", MOTORWAY, "01N", 180.0);
        assertThat(southbound).isNotEmpty().doesNotContainAnyElementsOf(northbound);
        assertThat(both).containsAll(northbound).containsAll(southbound);
        assertThat(strings("SELECT DISTINCT tunnel::text FROM road_segment WHERE id = ANY(?)", both))
                .containsOnly("false");
    }

    @Test
    void linkOnlyFindsRampsWhateverTheirNumber() {
        var ramp = jdbc.queryForMap("""
                SELECT id, ST_Y(ST_LineInterpolatePoint(geometry, 0.5)) lat, ST_X(ST_LineInterpolatePoint(geometry, 0.5)) lon
                FROM road_segment WHERE region = 'wellington' AND link AND ref IS NULL ORDER BY id LIMIT 1""");
        long id = ((Number) ramp.get("id")).longValue();
        GeoPoint point = new GeoPoint(((Number) ramp.get("lat")).doubleValue(), ((Number) ramp.get("lon")).doubleValue());

        Set<Long> links = segmentMatching.matchPoint("wellington", point, 50, "01N", null, true);

        assertThat(links).contains(id);
        assertThat(strings("SELECT DISTINCT link::text FROM road_segment WHERE id = ANY(?)", links))
                .containsOnly("true");
        // by road number the ramp without a number is not found
        assertThat(segmentMatching.matchPoint("wellington", point, 50, "01N", null)).doesNotContain(id);
    }

    @Test
    void outsideTheNetworkNothingMatches() {
        GeoPoint auckland = new GeoPoint(-36.8485, 174.7633);
        assertThat(segmentMatching.matchPoint("wellington", auckland, 50, "01N", null)).isEmpty();
        assertThat(segmentMatching.matchLine("wellington", new GeoLine(List.of(auckland,
                new GeoPoint(-36.85, 174.77))), null, null)).isEmpty();
    }

    @Test
    void regionWithoutNetworkIsUnavailable() {
        assertThatThrownBy(() -> segmentMatching.matchPoint("nz", TERRACE_TUNNEL, 50, null, null))
                .isInstanceOf(RoutingUnavailableException.class);
        assertThatThrownBy(() -> segmentMatching.matchLine("nz", MOTORWAY, null, null))
                .isInstanceOf(RoutingUnavailableException.class);
    }

    @Test
    void roadNumbersAreNormalised() {
        assertThat(refNormalizer.normalize("nz", "SH 1")).containsExactly("1");
        assertThat(refNormalizer.normalize("nz", "State Highway 1")).containsExactly("1");
        assertThat(refNormalizer.normalize("nz", "01N")).containsExactly("1");
        assertThat(refNormalizer.normalize("nz", "01S")).containsExactly("1");
        assertThat(refNormalizer.normalize("nz", "006")).containsExactly("6");
        assertThat(refNormalizer.normalize("nz", "SH 20A")).containsExactly("20A");
        assertThat(refNormalizer.normalize("nz", "SH 1;SH 2")).containsExactly("1", "2");
        assertThat(refNormalizer.normalize("de", "A 6")).containsExactly("A6");
        assertThat(refNormalizer.matches("nz", "01N", "SH 1")).isTrue();
        assertThat(refNormalizer.matches("nz", "006", "SH 1")).isFalse();
        assertThat(refNormalizer.matches("nz", "01N", null)).isFalse();
    }

    private List<String> strings(String sql, Collection<Long> ids) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setArray(1, connection.createArrayOf("bigint", ids.toArray()));
            return statement;
        }, (rs, row) -> rs.getString(1));
    }
}
