package com.nfv.network;

import com.nfv.network.logic.ExtractSource;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * Places during the import, see Docs/network.md#orte. The Wellington extract has no place nodes (filtered to roads when
 * it was cut), so the tests add some to a copy of it.
 */
@SpringBootTest
class RegionImportPlacesTest {
    private static final String REGION = "wellington";
    private static final String PLACES = """
            <node id="99000000001" lat="-41.2900" lon="174.7800" version="1">
              <tag k="place" v="city"/><tag k="name" v="Bigtown"/>
            </node>
            <node id="99000000002" lat="-41.3100" lon="174.7900" version="1">
              <tag k="place" v="village"/><tag k="name" v="Smallville"/>
            </node>
            """;

    @MockitoSpyBean
    private ExtractSource extractSource;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    private Path dir;

    @BeforeEach
    void extractWithPlaces() throws IOException {
        Path extract = dir.resolve("wellington-with-places.osm");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(
                Path.of("src/test/resources/osm/wellington.osm.gz")))) {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int firstNode = xml.indexOf("<node");
            Files.writeString(extract, xml.substring(0, firstNode) + PLACES + xml.substring(firstNode));
        }
        doReturn(extract).when(extractSource).fetch(any(), any());
    }

    @AfterEach
    void removePlaces() {
        jdbc.update("DELETE FROM place WHERE region = ?", REGION);
    }

    @Test
    void importWritesThePlacesOfTheExtract() {
        RegionStatus status = regionImport.importRegion(REGION);

        assertThat(status.state()).isEqualTo(RegionStatus.State.READY);
        assertThat(names()).containsExactly("Bigtown", "Smallville");
        assertThat(jdbc.queryForObject("SELECT kind FROM place WHERE name = 'Bigtown'", String.class))
                .isEqualTo("CITY");
        assertThat(jdbc.queryForList("SELECT DISTINCT extract_version_id FROM place", Long.class))
                .containsExactly(status.extractVersionId());
    }

    @Test
    void anotherImportReplacesThePlaces() {
        regionImport.importRegion(REGION);

        RegionStatus second = regionImport.importRegion(REGION);

        assertThat(names()).containsExactly("Bigtown", "Smallville");
        assertThat(jdbc.queryForList("SELECT DISTINCT extract_version_id FROM place", Long.class))
                .containsExactly(second.extractVersionId());
    }

    @Test
    void loadingAGraphWithoutPlacesAddsOnlyThePlaces() {
        long imported = regionImport.importRegion(REGION).extractVersionId();
        // as before V44: the graph is there, the places are not
        removePlaces();

        RegionStatus status = regionImport.loadOrImport(REGION);

        assertThat(status.extractVersionId()).isEqualTo(imported);
        assertThat(names()).containsExactly("Bigtown", "Smallville");
    }

    @Test
    void unreadablePlacesDoNotStopTheGraphFromLoading() {
        long imported = regionImport.importRegion(REGION).extractVersionId();
        removePlaces();
        doThrow(new IllegalStateException("no file")).when(extractSource).fetch(any(), any());

        RegionStatus status = regionImport.loadOrImport(REGION);

        assertThat(status.state()).isEqualTo(RegionStatus.State.READY);
        assertThat(status.extractVersionId()).isEqualTo(imported);
        assertThat(names()).isEmpty();
    }

    private List<String> names() {
        return jdbc.queryForList("SELECT name FROM place WHERE region = ? ORDER BY name", String.class, REGION);
    }
}
