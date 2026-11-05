package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoLine;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.RouteResult;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.network.logic.Routing;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Alternatives per leg and via points on central Wellington, see Docs/recommendations.md#tests. */
@SpringBootTest
class AlternativesAndViaPointsTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    private static final GeoPoint NEWTOWN = new GeoPoint(-41.3100, 174.7790);
    private static final GeoPoint THORNDON = new GeoPoint(-41.2755, 174.7790);
    private static final GeoPoint KELBURN = new GeoPoint(-41.2870, 174.7680);

    @Autowired
    private AlternativesGenerator generator;
    @Autowired
    private ViaPointExtractor viaPointExtractor;
    @Autowired
    private Routing routing;
    @Autowired
    private SegmentQueries segmentQueries;
    @Autowired
    private RegionImport regionImport;

    @BeforeEach
    void wellington() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.importRegion("wellington");
        }
    }

    @Test
    void legsAreCombinedWithoutTurningAtTheViaPoint() {
        // via point on a through road: the middle of the longest segment of the second alternative
        List<Candidate> direct = generator.generate(scenario(List.of()));
        RoutedSegment longest = direct.get(1).segments().stream()
                .max(Comparator.comparingDouble(RoutedSegment::distanceMeters)).orElseThrow();
        GeoPoint via = segmentQueries.segments(List.of(longest.segmentId())).get(longest.segmentId()).midpoint();
        Scenario scenario = scenario(List.of(new Scenario.Via(new RoutePoint(via, RoutePoint.Kind.VIA, null), 42L)));

        List<Candidate> candidates = generator.generate(scenario);

        assertThat(candidates).isNotEmpty().hasSizeLessThanOrEqualTo(scenario.count() + 1);
        assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.legs()).hasSize(2));
        assertThat(candidates).extracting(Candidate::duration).isSorted();
        assertThat(candidates).noneMatch(this::turnsAtVia);
    }

    @Test
    void viaPointOnDeadEndMayBeLeftTheWayItWasReached() {
        // Kelburn snaps to a short dead end: every combination turns there
        Scenario scenario = scenario(List.of(new Scenario.Via(new RoutePoint(KELBURN, RoutePoint.Kind.VIA, null),
                42L)));

        List<Candidate> candidates = generator.generate(scenario);

        assertThat(candidates).isNotEmpty().allMatch(this::turnsAtVia);
    }

    @Test
    void combinationTurningAtTheViaPointIsDropped() {
        RouteResult in = result(1L);
        RouteResult back = result(2L);
        RouteResult on = result(3L);
        Map<Long, SegmentInfo> segments = Map.of(1L, new SegmentInfo(1L, null, null, 10, NEWTOWN, 2L));

        assertThat(AlternativesGenerator.turns(in, back, segments)).isTrue();
        assertThat(AlternativesGenerator.turns(in, on, segments)).isFalse();
        assertThat(AlternativesGenerator.turns(in, in, segments)).as("via point within the segment").isFalse();
    }

    @Test
    void viaPointsReproduceTheSuggestion() {
        Scenario scenario = scenario(List.of());
        List<Candidate> candidates = generator.generate(scenario);
        assertThat(candidates).hasSizeGreaterThanOrEqualTo(2);
        Candidate base = candidates.getFirst();
        Set<Long> ids = new HashSet<>();
        candidates.forEach(c -> c.segments().forEach(s -> ids.add(s.segmentId())));
        Map<Long, SegmentInfo> segments = segmentQueries.segments(ids);

        assertThat(viaPointExtractor.extract(scenario, base, base, segments)).isEmpty();
        for (Candidate candidate : candidates.subList(1, candidates.size())) {
            List<SuggestedOption.Point> points = viaPointExtractor.extract(scenario, candidate, base, segments);
            assertThat(points).isNotEmpty();
            // as RoutePlanning plans it after applying: with curbsides at the stops
            List<RoutePoint> route = new ArrayList<>();
            route.add(scenario.start());
            points.forEach(p -> route.add(new RoutePoint(new GeoPoint(p.latitude(), p.longitude()),
                    RoutePoint.Kind.VIA, p.roadHint())));
            route.add(scenario.end());
            Set<Long> replanned = new HashSet<>();
            routing.route(route, RouteOptions.none()).segments().forEach(s -> replanned.add(s.segmentId()));
            assertThat(CandidateEvaluator.sharedRatio(ViaPointExtractor.steps(candidate.segments()), replanned))
                    .isGreaterThanOrEqualTo(0.9);
        }
    }

    private boolean turnsAtVia(Candidate candidate) {
        long last = candidate.legs().getFirst().segments().getLast().segmentId();
        return AlternativesGenerator.turns(candidate.legs().get(0), candidate.legs().get(1),
                segmentQueries.segments(List.of(last)));
    }

    private static Scenario scenario(List<Scenario.Via> vias) {
        return new Scenario(1L, SuggestionTrigger.PLANNING, null, new RoutePoint(NEWTOWN, RoutePoint.Kind.STOP, null),
                new RoutePoint(THORNDON, RoutePoint.Kind.STOP, null), vias,
                vias.isEmpty() ? WaypointMode.IGNORE : WaypointMode.FOLLOW, DEPARTURE, RouteOptions.none(), List.of(),
                Set.of(OptionKind.ALTERNATIVES), 3);
    }

    private static RouteResult result(long segmentId) {
        return new RouteResult("wellington", 1, 10, Duration.ofSeconds(1), new GeoLine(List.of(NEWTOWN)),
                List.of(new RoutedSegment(segmentId, 0, 1, 10, Duration.ofSeconds(1))), List.of());
    }
}