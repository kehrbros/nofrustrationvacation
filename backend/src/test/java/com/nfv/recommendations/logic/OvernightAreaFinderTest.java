package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.PlaceKind;
import com.nfv.network.logic.Places.PlaceNearRoute;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.SectionPart;
import com.nfv.recommendations.logic.Stopovers.OvernightArea;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Ten sections of 10 km and 10 minutes, see Docs/advisor.md#find_overnight_areas. */
class OvernightAreaFinderTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2027, 7, 12, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime NO_LIMIT = DEPARTURE.plusDays(1);
    private static final int SPACING = 15_000;
    private static final List<SectionPart> PARTS = LongStream.range(0, 10)
            .mapToObj(id -> new SectionPart(id, 0, 1)).toList();
    private static final CandidateEvaluator.Outcome OUTCOME = CandidateEvaluator.evaluate(
            LongStream.range(0, 10).mapToObj(id -> new CandidateEvaluator.Step(id, 10_000, Duration.ofMinutes(10)))
                    .toList(),
            DEPARTURE, RouteOptions.none(), List.of());

    @Test
    void arrivalAndRemainingComeFromThePositionInTheClosestSection() {
        OvernightArea area = select(List.of(town("A", 2, 0.5)), null, NO_LIMIT, 3).getFirst();

        assertThat(area.arrivalAt()).isEqualTo(DEPARTURE.plusMinutes(25));
        assertThat(area.remainingMeters()).isCloseTo(75_000, within(0.1));
    }

    @Test
    void positionCountsWithinTheDrivenPartOfTheSegment() {
        List<SectionPart> parts = new java.util.ArrayList<>(PARTS);
        parts.set(2, new SectionPart(2, 0.5, 1));

        OvernightArea inside = OvernightAreaFinder.select(OUTCOME, parts, List.of(town("A", 2, 0.75)), null, NO_LIMIT,
                3, SPACING).getFirst();
        OvernightArea before = OvernightAreaFinder.select(OUTCOME, parts, List.of(town("B", 2, 0.25)), null, NO_LIMIT,
                3, SPACING).getFirst();

        assertThat(inside.arrivalAt()).isEqualTo(DEPARTURE.plusMinutes(25));
        assertThat(before.arrivalAt()).isEqualTo(DEPARTURE.plusMinutes(20));
    }

    @Test
    void placesAtOrBehindTheObstacleAreDropped() {
        List<OvernightArea> areas = select(List.of(town("Before", 3, 0.5), town("At", 5, 0.5), town("Behind", 7, 0.5)),
                5, NO_LIMIT, 5);

        assertThat(areas).extracting(OvernightArea::name).containsExactly("Before");
    }

    @Test
    void placesReachedAfterTheLimitAreDropped() {
        List<OvernightArea> areas = select(List.of(town("Early", 1, 0), town("Late", 4, 0)), null,
                DEPARTURE.plusMinutes(30), 5);

        assertThat(areas).extracting(OvernightArea::name).containsExactly("Early");
    }

    @Test
    void placesNextToTheTargetStopAreDropped() {
        List<OvernightArea> areas = select(List.of(town("Near", 8, 0), town("AtTarget", 9, 0)), null, NO_LIMIT, 5);

        assertThat(areas).extracting(OvernightArea::name).containsExactly("Near");
    }

    @Test
    void townsComeFirstAndTheClosestPlaceTakesTheLastSlot() {
        List<OvernightArea> areas = select(List.of(town("Far", 1, 0), town("Middle", 3, 0), town("Close", 5, 0),
                village("ByTheObstacle", 7, 0)), 8, NO_LIMIT, 3);

        assertThat(areas).extracting(OvernightArea::name).containsExactly("ByTheObstacle", "Close", "Middle");
    }

    @Test
    void villageRightNextToATownDoesNotPushItOut() {
        List<OvernightArea> areas = select(List.of(town("Town", 6, 0.5), village("Suburb", 7, 0)), 8, NO_LIMIT, 3);

        assertThat(areas).extracting(OvernightArea::name).containsExactly("Town");
    }

    @Test
    void placesStayApartAndVillagesFillWhatIsLeft() {
        List<OvernightArea> areas = select(List.of(town("Close", 7, 0), town("Suburb", 6, 0),
                village("Village", 4, 0), village("TooNear", 3, 8)), 8, NO_LIMIT, 5);

        assertThat(areas).extracting(OvernightArea::name).containsExactly("Close", "Village");
    }

    @Test
    void oneWantedIsTheClosestPlace() {
        List<OvernightArea> areas = select(List.of(town("Town", 2, 0), village("Village", 7, 0)), 8, NO_LIMIT, 1);

        assertThat(areas).extracting(OvernightArea::name).containsExactly("Village");
    }

    @Test
    void nothingReachableIsEmpty() {
        assertThat(select(List.of(town("Behind", 5, 0)), 2, NO_LIMIT, 3)).isEmpty();
        assertThat(select(List.of(), null, NO_LIMIT, 3)).isEmpty();
    }

    private static List<OvernightArea> select(List<PlaceNearRoute> places, Integer obstacle, OffsetDateTime limit,
                                              int wanted) {
        return OvernightAreaFinder.select(OUTCOME, PARTS, places, obstacle, limit, wanted, SPACING);
    }

    private static PlaceNearRoute town(String name, int index, double fraction) {
        return new PlaceNearRoute(name, PlaceKind.TOWN, new GeoPoint(-41, 174), index, fraction, 100);
    }

    private static PlaceNearRoute village(String name, int index, double fraction) {
        return new PlaceNearRoute(name, PlaceKind.VILLAGE, new GeoPoint(-41, 174), index, fraction, 100);
    }
}
