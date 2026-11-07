package com.nfv.recommendations.logic;

import com.nfv.network.logic.PlaceKind;
import com.nfv.network.logic.Places.PlaceNearRoute;
import com.nfv.network.logic.SectionPart;
import com.nfv.recommendations.logic.Stopovers.OvernightArea;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Which places to offer for a stay along a way, see Docs/advisor.md#find_overnight_areas. Pure function. */
final class OvernightAreaFinder {
    private OvernightAreaFinder() {
    }

    /**
     * @param outcome  the way with one passage per entry of {@code parts}
     * @param obstacle position of the first section that cannot be used, {@code null} if the way is free
     * @return closest to the obstacle or the end of the way first
     */
    static List<OvernightArea> select(CandidateEvaluator.Outcome outcome, List<SectionPart> parts,
                                      List<PlaceNearRoute> places, @Nullable Integer obstacle,
                                      OffsetDateTime latestArrival, int wanted, int minSpacingMeters) {
        List<OvernightArea> reachable = places.stream()
                .filter(place -> obstacle == null || place.pathIndex() < obstacle)
                .map(place -> area(outcome, parts, place))
                .filter(area -> !area.arrivalAt().isAfter(latestArrival))
                .filter(area -> area.remainingMeters() >= minSpacingMeters)
                .sorted(Comparator.comparingDouble(OvernightArea::remainingMeters))
                .toList();
        if (reachable.isEmpty()) {
            return List.of();
        }
        List<OvernightArea> selected = new ArrayList<>();
        reachable.stream().filter(area -> area.kind() != PlaceKind.VILLAGE)
                .forEach(area -> addIfRoom(selected, area, wanted, minSpacingMeters));
        // the closest place counts even if it is small, unless a larger one is right next to it
        OvernightArea closest = reachable.getFirst();
        if (isSpaced(selected, closest, minSpacingMeters)) {
            if (selected.size() == wanted) {
                selected.removeLast();
            }
            selected.add(closest);
        }
        reachable.stream().filter(area -> area.kind() == PlaceKind.VILLAGE)
                .forEach(area -> addIfRoom(selected, area, wanted, minSpacingMeters));
        return selected.stream().sorted(Comparator.comparingDouble(OvernightArea::remainingMeters)).toList();
    }

    private static void addIfRoom(List<OvernightArea> selected, OvernightArea area, int wanted, int minSpacingMeters) {
        if (selected.size() < wanted && isSpaced(selected, area, minSpacingMeters)) {
            selected.add(area);
        }
    }

    private static boolean isSpaced(List<OvernightArea> selected, OvernightArea area, int minSpacingMeters) {
        return selected.stream().allMatch(
                other -> Math.abs(other.remainingMeters() - area.remainingMeters()) >= minSpacingMeters);
    }

    /** Time and distance from the passage of the closest section, by the part of it driven until the place. */
    private static OvernightArea area(CandidateEvaluator.Outcome outcome, List<SectionPart> parts,
                                      PlaceNearRoute place) {
        CandidateEvaluator.Passage passage = outcome.passages().get(place.pathIndex());
        SectionPart part = parts.get(place.pathIndex());
        double span = part.toFraction() - part.fromFraction();
        double share = span <= 0 ? 0 : Math.clamp((place.segmentFraction() - part.fromFraction()) / span, 0.0, 1.0);
        Duration driven = Duration.ofMillis(
                Math.round(Duration.between(passage.start(), passage.end()).toMillis() * share));
        double remaining = outcome.distanceMeters() - passage.metersBefore() - passage.meters() * share;
        return new OvernightArea(place.name(), place.kind(), place.point().lat(), place.point().lon(),
                passage.start().plus(driven), remaining, place.offRouteMeters());
    }
}
