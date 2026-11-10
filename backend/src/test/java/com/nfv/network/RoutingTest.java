package com.nfv.network;

import com.nfv.network.logic.AlternativeOptions;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.NoRouteException;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.RouteResult;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.network.logic.Routing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Routing on central Wellington, see Docs/network.md#routing-details. */
@SpringBootTest
class RoutingTest {
    // Te Aro to Thorndon, the urban motorway (SH 1) between them runs through the Terrace Tunnel
    private static final GeoPoint TE_ARO = new GeoPoint(-41.2947, 174.7735);
    private static final GeoPoint THORNDON = new GeoPoint(-41.2755, 174.7790);
    // Walter Street right next to the start: without pass_through the route would turn there
    private static final GeoPoint WALTER_STREET = new GeoPoint(-41.2949086, 174.7731836);
    private static final GeoPoint AUCKLAND = new GeoPoint(-36.8485, 174.7633);
    // Te Aro to Thorndon (3 km) is too short for alternatives, Newtown to Thorndon (5 km) has several
    private static final GeoPoint NEWTOWN = new GeoPoint(-41.3100, 174.7790);
    private static final AlternativeOptions ALTERNATIVES = new AlternativeOptions(4, 1.4, 0.6);

    @Autowired
    private Routing routing;
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
    void routeReturnsSegmentsWithTimesAndFractions() {
        RouteResult result = route(RouteOptions.none(), stop(TE_ARO), stop(THORNDON));

        List<Long> ids = result.segments().stream().map(RoutedSegment::segmentId).toList();
        assertThat(count("SELECT COUNT(DISTINCT id) FROM road_segment WHERE id = ANY(?)", ids))
                .isEqualTo(new HashSet<>(ids).size());
        assertThat(result.segments().stream().mapToDouble(RoutedSegment::distanceMeters).sum())
                .isCloseTo(result.distanceMeters(), within(result.distanceMeters() * 0.01));
        assertThat(result.segments().stream().map(RoutedSegment::duration).reduce(Duration.ZERO, Duration::plus)
                .toMillis()).isCloseTo(result.duration().toMillis(), within(result.duration().toMillis() / 100));
        assertThat(tunnelSegments(ids)).as("Terrace Tunnel").isNotEmpty();
        RoutedSegment first = result.segments().getFirst();
        RoutedSegment last = result.segments().getLast();
        assertThat(first.fromFraction() > 0 || last.toFraction() < 1).isTrue();
        assertThat(result.segments().subList(1, result.segments().size() - 1))
                .allSatisfy(segment -> {
                    assertThat(segment.fromFraction()).isZero();
                    assertThat(segment.toFraction()).isEqualTo(1);
                });
        assertThat(result.snappedPoints()).hasSize(2);
        assertThat(result.snappedPoints().getLast().segmentPosition()).isEqualTo(result.segments().size() - 1);
    }

    @Test
    void blockedSegmentsForceDetour() {
        RouteResult normal = route(RouteOptions.none(), stop(TE_ARO), stop(THORNDON));
        Set<Long> tunnel = new HashSet<>(tunnelSegments(
                normal.segments().stream().map(RoutedSegment::segmentId).toList()));

        RouteResult detour = route(RouteOptions.blocked(tunnel), stop(TE_ARO), stop(THORNDON));

        assertThat(detour.segments()).extracting(RoutedSegment::segmentId).doesNotContainAnyElementsOf(tunnel);
        assertThat(detour.duration()).isGreaterThan(normal.duration());
    }

    @Test
    void largeDelayForcesDetour() {
        Set<Long> tunnel = tunnelOnFastestRoute();

        RouteResult detour = route(delayed(tunnel, Duration.ofHours(1)), stop(TE_ARO), stop(THORNDON));

        assertThat(detour.segments()).extracting(RoutedSegment::segmentId).doesNotContainAnyElementsOf(tunnel);
    }

    @Test
    void smallDelayKeepsRoute() {
        Set<Long> tunnel = tunnelOnFastestRoute();

        RouteResult result = route(delayed(tunnel, Duration.ofSeconds(1)), stop(TE_ARO), stop(THORNDON));

        assertThat(result.segments()).extracting(RoutedSegment::segmentId).containsAll(tunnel);
    }

    @Test
    void negativeDelayIsRejected() {
        assertThatThrownBy(() -> new RouteOptions(Set.of(), Map.of(1L, Duration.ofSeconds(-1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alternativesStartWithFastest() {
        RouteResult fastest = route(RouteOptions.none(), stop(NEWTOWN), stop(THORNDON));

        List<RouteResult> alternatives = routing.alternatives(stop(NEWTOWN), stop(THORNDON), RouteOptions.none(),
                ALTERNATIVES);

        assertThat(alternatives).hasSizeBetween(2, ALTERNATIVES.maxPaths());
        assertThat(alternatives.getFirst().duration())
                .as("same request as route() between two stops, so a suggestion can be planned again")
                .isCloseTo(fastest.duration(), Duration.ofSeconds(1));
        assertThat(alternatives).allSatisfy(alternative -> {
            assertThat(alternative.segments()).isNotEmpty();
            assertThat(alternative.snappedPoints()).hasSize(2);
            assertThat(alternative.duration()).isGreaterThanOrEqualTo(alternatives.getFirst().duration());
        });
        assertThat(alternatives).extracting(this::segmentIds).doesNotHaveDuplicates();
    }

    @Test
    void alternativesAvoidBlockedSegments() {
        Set<Long> tunnel = tunnelOnFastestRoute();

        List<RouteResult> alternatives = routing.alternatives(stop(TE_ARO), stop(THORNDON),
                RouteOptions.blocked(tunnel), ALTERNATIVES);

        assertThat(alternatives).isNotEmpty().allSatisfy(alternative -> assertThat(alternative.segments())
                .extracting(RoutedSegment::segmentId).doesNotContainAnyElementsOf(tunnel));
    }

    @Test
    void alternativesNeedTwoStops() {
        assertThatThrownBy(() -> routing.alternatives(stop(TE_ARO),
                new RoutePoint(THORNDON, RoutePoint.Kind.VIA, null), RouteOptions.none(), ALTERNATIVES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Set<Long> tunnelOnFastestRoute() {
        RouteResult normal = route(RouteOptions.none(), stop(TE_ARO), stop(THORNDON));
        Set<Long> tunnel = new HashSet<>(tunnelSegments(segmentIds(normal)));
        assertThat(tunnel).as("Terrace Tunnel on the fastest route").isNotEmpty();
        return tunnel;
    }

    private static RouteOptions delayed(Set<Long> segmentIds, Duration delay) {
        return new RouteOptions(Set.of(), segmentIds.stream().collect(Collectors.toMap(id -> id, id -> delay)));
    }

    private List<Long> segmentIds(RouteResult result) {
        return result.segments().stream().map(RoutedSegment::segmentId).toList();
    }

    @Test
    void viaPointIsPassedThrough() {
        RouteResult result = route(RouteOptions.none(), stop(TE_ARO), new RoutePoint(WALTER_STREET,
                RoutePoint.Kind.VIA, null), stop(TE_ARO));

        assertThat(result.distanceMeters()).as("no u-turn at the via point").isGreaterThan(300);
        assertThat(result.snappedPoints()).hasSize(3);
        assertThat(result.snappedPoints().get(1).segmentPosition()).isBetween(0, result.segments().size() - 1);
    }

    @Test
    void stopOnOneWayStreetStillRoutes() {
        RouteResult result = route(RouteOptions.none(), stop(TE_ARO), stop(THORNDON));

        assertThat(result.segments()).isNotEmpty();
    }

    @Test
    void pointOutsideRegionIsRejected() {
        assertThatThrownBy(() -> route(RouteOptions.none(), stop(TE_ARO), stop(AUCKLAND)))
                .isInstanceOf(NoRouteException.class);
    }

    @Test
    void stopBetweenStopsIsRejected() {
        assertThatThrownBy(() -> route(RouteOptions.none(), stop(TE_ARO), stop(WALTER_STREET), stop(THORNDON)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RouteResult route(RouteOptions options, RoutePoint... points) {
        return routing.route(List.of(points), options);
    }

    private static RoutePoint stop(GeoPoint point) {
        return new RoutePoint(point, RoutePoint.Kind.STOP, null);
    }

    private List<Long> tunnelSegments(List<Long> ids) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(
                    "SELECT id FROM road_segment WHERE id = ANY(?) AND tunnel AND ref = 'SH 1'");
            statement.setArray(1, connection.createArrayOf("bigint", ids.toArray()));
            return statement;
        }, (rs, row) -> rs.getLong(1));
    }

    private long count(String sql, List<Long> ids) {
        Long count = jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setArray(1, connection.createArrayOf("bigint", ids.toArray()));
            return statement;
        }, rs -> rs.next() ? rs.getLong(1) : 0L);
        return count == null ? 0 : count;
    }
}