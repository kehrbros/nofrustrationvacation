package com.nfv.incidents.logic;

import com.nfv.incidents.entities.Consequence;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.SectionPart;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.entities.RouteSection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stretch of its route a consequence covers, as a line for the map: from the first affected section on, as long as
 * the following sections lie on segments of the incident. Derived on request, not stored.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsequenceLines {
    private final RouteSectionRepository routeSectionRepository;
    private final SegmentQueries segmentQueries;

    /** Consequence id → points in driving order; empty if the section is gone or the segments are unknown. */
    public Map<Long, List<GeoPoint>> of(Collection<Consequence> consequences) {
        Map<Long, List<RouteSection>> sectionsByRoute = new HashMap<>();
        Map<Long, List<GeoPoint>> lines = new HashMap<>();
        for (Consequence consequence : consequences) {
            List<RouteSection> sections = sectionsByRoute.computeIfAbsent(consequence.getRouteId(),
                    routeSectionRepository::findByRouteIdOrderByPosition);
            List<SectionPart> parts = affected(sections, consequence.getRouteSectionId(),
                    consequence.getIncident().getSegmentIds());
            lines.put(consequence.getId(), parts.isEmpty() ? List.of() : segmentQueries.line(parts).points());
        }
        return lines;
    }

    /** Sections from the first affected one on, while they lie on segments of the incident. */
    static List<SectionPart> affected(List<RouteSection> sections, long firstSectionId, Set<Long> segmentIds) {
        List<SectionPart> parts = new ArrayList<>();
        boolean started = false;
        for (RouteSection section : sections) {
            started = started || section.getId() == firstSectionId;
            if (!started) {
                continue;
            }
            if (!segmentIds.contains(section.getSegmentId())) {
                break;
            }
            parts.add(new SectionPart(section.getSegmentId(), section.getFromFraction(), section.getToFraction()));
        }
        return parts;
    }
}
