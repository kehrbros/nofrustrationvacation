package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoLine;
import com.nfv.network.logic.RouteResult;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.network.logic.SectionPart;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.recommendations.config.RecommendationProperties;
import com.nfv.trips.logic.PlannedSection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The planned route with another departure, at most one in each direction: past the end of all incidents or before
 * the start of all of them, see Docs/recommendations.md#generators. No routing, only other passage times. The
 * evaluation drops a departure that runs into another closure.
 */
@Component
@RequiredArgsConstructor
class DepartureShiftGenerator implements CandidateGenerator {
    private final SegmentQueries segmentQueries;
    private final RecommendationProperties properties;

    @Override
    public OptionKind kind() {
        return OptionKind.DEPARTURE_SHIFT;
    }

    @Override
    public List<Candidate> generate(Scenario scenario) {
        if (scenario.reference().isEmpty() || scenario.avoid().isEmpty()) {
            return List.of();
        }
        List<OffsetDateTime> departures = Stream.of(later(scenario.avoid(), scenario.departure(),
                                properties.departureShiftBuffer(), properties.departureShiftMax()),
                        earlier(scenario.avoid(), scenario.departure(), Objects.requireNonNull(scenario.earliestDeparture()),
                                properties.departureShiftBuffer()))
                .flatMap(Optional::stream)
                .toList();
        if (departures.isEmpty()) {
            return List.of();
        }
        RouteResult planned = planned(scenario.reference());
        return departures.stream()
                .map(departure -> new Candidate(OptionKind.DEPARTURE_SHIFT, List.of(planned), departure))
                .toList();
    }

    /** Reaches every place after its end plus buffer; none if one has no end or it would take longer than the limit. */
    static Optional<OffsetDateTime> later(List<Scenario.Avoid> avoid, OffsetDateTime departure, Duration buffer,
                                          Duration max) {
        if (avoid.stream().anyMatch(a -> a.until() == null)) {
            return Optional.empty();
        }
        return avoid.stream()
                .map(a -> Objects.requireNonNull(a.until()).plus(buffer).minus(a.reachedAfter()))
                .max(Comparator.naturalOrder())
                .filter(at -> at.isAfter(departure) && !at.isAfter(departure.plus(max)));
    }

    /** Leaves every place before its start minus buffer; none if that is before {@code earliest}. */
    static Optional<OffsetDateTime> earlier(List<Scenario.Avoid> avoid, OffsetDateTime departure,
                                            OffsetDateTime earliest, Duration buffer) {
        return avoid.stream()
                .map(a -> a.from().minus(buffer).minus(a.leftAfter()))
                .min(Comparator.naturalOrder())
                .filter(at -> at.isBefore(departure) && !at.isBefore(earliest));
    }

    /** The planned sections as a route result, so that evaluation and ranking need no special case. */
    private RouteResult planned(List<PlannedSection> sections) {
        Map<Long, SegmentInfo> segments = segmentQueries.segments(
                sections.stream().map(PlannedSection::segmentId).collect(Collectors.toSet()));
        List<RoutedSegment> routed = sections.stream().map(section -> {
            SegmentInfo info = segments.get(section.segmentId());
            double length = info == null ? 0 : info.lengthMeters();
            return new RoutedSegment(section.segmentId(), section.fromFraction(), section.toFraction(),
                    length * (section.toFraction() - section.fromFraction()),
                    Duration.between(section.expectedPassageStart(), section.expectedPassageEnd()));
        }).toList();
        GeoLine line = segmentQueries.line(sections.stream()
                .map(s -> new SectionPart(s.segmentId(), s.fromFraction(), s.toFraction())).toList());
        return new RouteResult("", 0, routed.stream().mapToDouble(RoutedSegment::distanceMeters).sum(),
                routed.stream().map(RoutedSegment::duration).reduce(Duration.ZERO, Duration::plus), line, routed,
                List.of());
    }
}
