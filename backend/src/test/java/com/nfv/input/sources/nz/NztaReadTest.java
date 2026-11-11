package com.nfv.input.sources.nz;

import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentLocation;
import com.nfv.input.logic.FetchContext;
import com.nfv.input.logic.SourceItem;
import com.nfv.input.logic.TrafficReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** NztaSource.read over all 197 events of the sample, field table and type rules of Docs/traffic-feed.md#first-source-nzta. */
class NztaReadTest {
    private static NztaSource source;
    private static Map<String, String> payloads;
    private static Map<String, TrafficReport> reports;

    @BeforeAll
    static void readSample() {
        source = new NztaSource(NztaSourceTest.properties("classpath:sources/nz-nzta/events.json"),
                new DefaultResourceLoader(), "https://app.example");
        payloads = source.fetch(new FetchContext(null, null, OffsetDateTime.now(), null)).items().stream()
                .collect(Collectors.toMap(SourceItem::subjectId, SourceItem::payload, (a, b) -> a, LinkedHashMap::new));
        reports = payloads.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> source.read(e.getValue())));
    }

    @Test
    void everyEventIsReadWithTheTypeRules() {
        assertThat(reports).hasSize(197);
        assertThat(count(reports.values(), TrafficReport::type)).containsExactlyInAnyOrderEntriesOf(Map.of(
                IncidentType.CLOSURE, 32L, IncidentType.ROADWORKS, 90L, IncidentType.OTHER, 61L,
                IncidentType.CONGESTION, 11L, IncidentType.ACCIDENT, 3L));
        assertThat(reports.values()).filteredOn(TrafficReport::resolved).hasSize(30);
        assertThat(reports.values()).allSatisfy(report -> {
            assertThat(report.location()).isNotNull();
            assertThat(report.title()).isNotBlank();
            assertThat(report.direction()).isNull();
            assertThat(report.validTo() == null || !report.validTo().isBefore(report.validFrom())).isTrue();
        });
        assertThat(count(reports.values(), report -> report.location().getClass().getSimpleName()))
                .containsExactlyInAnyOrderEntriesOf(Map.of("Point", 121L, "Lines", 76L));
    }

    @Test
    void localRoadsHaveNoRoadNumber() {
        assertThat(reports.entrySet()).filteredOn(entry -> entry.getValue().roadRef() == null)
                .extracting(Map.Entry::getKey).containsExactlyInAnyOrder("554438", "565171", "565570");
        assertThat(reports.values()).filteredOn(report -> report.roadRef() != null)
                .allSatisfy(report -> assertThat(report.roadRef()).isNotBlank());
    }

    @Test
    void rampsAndLinksAloneAreMarked() {
        assertThat(List.of("566345", "566370", "566371", "566372", "566404", "566412", "566413"))
                .allSatisfy(id -> assertThat(reports.get(id).linkOnly()).as(id).isTrue());
        // full closures "incl link to …" concern the road itself
        assertThat(List.of("566364", "566366", "566367", "566368"))
                .allSatisfy(id -> assertThat(reports.get(id).linkOnly()).as(id).isFalse());
        assertThat(reports.get("566003").linkOnly()).isFalse();
    }

    @Test
    void terraceTunnelClosure() {
        TrafficReport tunnel = reports.get("566003");

        assertThat(tunnel.type()).isEqualTo(IncidentType.CLOSURE);
        assertThat(tunnel.resolved()).isFalse();
        assertThat(tunnel.validFrom()).isEqualTo(OffsetDateTime.parse("2026-11-01T21:00:26+13:00"));
        assertThat(tunnel.validTo()).isEqualTo(OffsetDateTime.parse("2026-12-18T04:30:26+13:00"));
        assertThat(tunnel.roadRef()).isEqualTo("01N");
        assertThat(tunnel.location()).isEqualTo(new IncidentLocation.Point(
                new IncidentLocation.Coordinate(-41.289262576382875, 174.77157677044664)));
        assertThat(tunnel.assumedDelay()).isNull();
        assertThat(tunnel.title()).isEqualTo("Road Work: SH 1 Terrace Tunnel, northbound");
        assertThat(tunnel.description()).contains("between 9pm and 4:30am")
                .endsWith("01N-1068/05.95-B   TERRACE TUNNEL START TO TERRACE TUNNEL END");
    }

    @Test
    void scheduledMotorwayWarningAsLine() {
        TrafficReport motorway = reports.get("566353");

        assertThat(motorway.type()).isEqualTo(IncidentType.OTHER);
        assertThat(motorway.validFrom()).isAfter(OffsetDateTime.parse("2026-11-08T21:00:00+13:00"));
        assertThat(motorway.assumedDelay()).isEqualTo(Duration.ZERO);
        assertThat(motorway.location()).isInstanceOfSatisfying(IncidentLocation.Lines.class,
                lines -> assertThat(lines.lines()).singleElement().satisfies(line -> {
                    assertThat(line).hasSize(5);
                    assertThat(line.getFirst()).isEqualTo(new IncidentLocation.Coordinate(-41.28712008702765,
                            174.77176000000824));
                }));
    }

    @Test
    void endFromExpectedResolutionOrOpen() {
        // no endDate: "Until further notice" → open, otherwise local time in Pacific/Auckland
        assertThat(reports.get("554258").validTo()).isNull();
        assertThat(reports.get("553074").validTo()).isEqualTo(OffsetDateTime.parse("2027-03-31T18:00+13:00"));
        assertThat(reports.values().stream().filter(report -> report.validTo() == null)).hasSize(12);
    }

    @Test
    void assumedDelayPerImpactFromConfiguration() {
        Map<IncidentType, Collection<Duration>> byType = reports.values().stream().collect(Collectors.groupingBy(
                TrafficReport::type, Collectors.mapping(TrafficReport::assumedDelay, Collectors.toCollection(
                        java.util.ArrayList::new))));
        assertThat(byType.get(IncidentType.CLOSURE)).containsOnlyNulls();
        assertThat(byType.get(IncidentType.ACCIDENT)).containsOnly(Duration.ofMinutes(30));
        // "delays" is set to 20 min in the test configuration, Caution stays at the default 0
        assertThat(byType.get(IncidentType.CONGESTION)).containsOnly(Duration.ofMinutes(20));
        assertThat(reports.values().stream().filter(report -> Duration.ofMinutes(20).equals(report.assumedDelay())))
                .hasSize(28);
        assertThat(reports.values().stream().filter(report -> Duration.ZERO.equals(report.assumedDelay())))
                .hasSize(134);
    }

    @Test
    void locationsAsTextOrList() {
        assertThat(reports.get("554258").description())
                .contains("CEMETERY ENTRANCE TO JED RIVER BRIDGE")
                .contains("KAHUTARA RIVER BRDG NTH ABUT");
        String withoutLocations = JsonMapper.builder().build().readTree(payloads.get("566003")).toString()
                .replace("\"locations\":\"01N-1068/05.95-B   TERRACE TUNNEL START TO TERRACE TUNNEL END\",", "")
                .replace("\"journey\":{", "\"journeyOld\":{");
        assertThat(source.read(withoutLocations).description()).doesNotContain("TERRACE TUNNEL START");
    }

    @Test
    void unreadablePayloadIsRejected() {
        assertThatThrownBy(() -> source.read("{\"id\": 1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.read("{\"id\": 1, \"geometry\": \"POINT (1\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static <K> Map<K, Long> count(Collection<TrafficReport> reports, Function<TrafficReport, K> key) {
        return reports.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
    }
}
