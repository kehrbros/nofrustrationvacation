package com.nfv.network;

import com.nfv.network.data.ExtractVersionRepository;
import com.nfv.network.entities.ExtractStatus;
import com.nfv.network.entities.ExtractVersion;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Import of central Wellington (src/test/resources/osm), see Docs/network.md#tests. */
@SpringBootTest
class RegionImportTest {
    private static final String REGION = "wellington";
    private static final Path GRAPHS = Path.of("target/graphs/wellington");

    @Autowired
    private RegionImport regionImport;
    @Autowired
    private ExtractVersionRepository versionRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void importWritesSegmentsAndMapping() {
        RegionStatus status = regionImport.importRegion(REGION);

        assertThat(status.state()).isEqualTo(RegionStatus.State.READY);
        ExtractVersion version = versionRepository.findById(status.extractVersionId()).orElseThrow();
        assertThat(version.getStatus()).isEqualTo(ExtractStatus.READY);
        assertThat(version.getSegmentCount()).isGreaterThan(10_000);
        assertThat(count("SELECT COUNT(*) FROM road_segment WHERE region = ? AND last_seen_version_id = ?",
                REGION, version.getId())).isEqualTo(version.getSegmentCount().longValue());
        // Identical duplicate edges share a segment, so there are at least as many edge keys as segments
        assertThat(count("SELECT COUNT(*) FROM edge_mapping WHERE extract_version_id = ?", version.getId()))
                .isGreaterThanOrEqualTo(version.getSegmentCount());
        assertThat(count("""
                SELECT COUNT(*) FROM road_segment WHERE region = ? AND tunnel AND ref = 'SH 1'""", REGION))
                .as("Terrace Tunnel and Mount Victoria Tunnel").isPositive();
        assertThat(count("""
                SELECT COUNT(*) FROM road_segment
                WHERE region = ? AND (NOT ST_IsValid(geometry) OR ST_SRID(geometry) <> 4326)""", REGION)).isZero();
    }

    @Test
    void reimportKeepsSegmentIdsAndDropsTheOldVersion() {
        long first = regionImport.importRegion(REGION).extractVersionId();
        List<Long> idsBefore = segmentIds(first);

        long second = regionImport.importRegion(REGION).extractVersionId();

        assertThat(segmentIds(second)).isEqualTo(idsBefore);
        assertThat(count("SELECT COUNT(*) FROM edge_mapping WHERE extract_version_id = ?", first)).isZero();
        assertThat(count("SELECT COUNT(*) FROM road_segment WHERE region = ? AND retired_at IS NOT NULL", REGION))
                .isZero();
        assertThat(Files.exists(GRAPHS.resolve(Long.toString(first)))).isFalse();
        assertThat(Files.isDirectory(GRAPHS.resolve(Long.toString(second)))).isTrue();
    }

    @Test
    void existingGraphIsLoadedWithoutNewImport() {
        long imported = regionImport.importRegion(REGION).extractVersionId();
        long versions = versionRepository.count();

        RegionStatus status = regionImport.loadOrImport(REGION);

        assertThat(status.state()).isEqualTo(RegionStatus.State.READY);
        assertThat(status.extractVersionId()).isEqualTo(imported);
        assertThat(versionRepository.count()).isEqualTo(versions);
    }

    @Test
    void segmentsWithoutLinkFlagAreImportedAgain() {
        long imported = regionImport.importRegion(REGION).extractVersionId();
        List<Long> idsBefore = segmentIds(imported);
        assertThat(count("SELECT COUNT(*) FROM road_segment WHERE region = ? AND link", REGION)).isPositive();
        // as after V37: the graph is there, the flag is not filled
        jdbc.update("UPDATE road_segment SET link = FALSE WHERE region = ?", REGION);

        RegionStatus status = regionImport.loadOrImport(REGION);

        assertThat(status.state()).isEqualTo(RegionStatus.State.READY);
        assertThat(status.extractVersionId()).isNotEqualTo(imported);
        assertThat(count("SELECT COUNT(*) FROM road_segment WHERE region = ? AND link", REGION)).isPositive();
        assertThat(segmentIds(status.extractVersionId())).isEqualTo(idsBefore);
    }

    private List<Long> segmentIds(long versionId) {
        return jdbc.queryForList(
                "SELECT id FROM road_segment WHERE last_seen_version_id = ? ORDER BY id", Long.class, versionId);
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }
}