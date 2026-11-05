package com.nfv.incidents.logic;

import com.nfv.incidents.data.IncidentRepository;
import com.nfv.incidents.entities.Incident;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.network.logic.GeoLine;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.RoutingUnavailableException;
import com.nfv.network.logic.SegmentMatching;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.trips.data.RouteSectionRepository;
import jakarta.annotation.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Entry point for incidents reported by sources ({@code input}): locates them on road segments and creates or changes
 * the incident, see Docs/traffic-feed.md#verarbeitung-trafficeventprocessor.
 * <ul>
 *     <li>point: segments within {@code nfv.incidents.point-radius-meters} with the road number</li>
 *     <li>lines: map matching, with the road number</li>
 *     <li>areas: not yet ({@code AREA_NOT_SUPPORTED})</li>
 * </ul>
 */
@Service
public class IncidentReporting {
    private static final int MAX_DESCRIPTION = 2000;

    private final IncidentLogic incidentLogic;
    private final IncidentRepository incidentRepository;
    private final SegmentMatching segmentMatching;
    private final SegmentQueries segmentQueries;
    private final RouteSectionRepository routeSectionRepository;
    private final Clock clock;
    private final double pointRadiusMeters;

    public IncidentReporting(IncidentLogic incidentLogic, IncidentRepository incidentRepository,
                             SegmentMatching segmentMatching, SegmentQueries segmentQueries,
                             RouteSectionRepository routeSectionRepository, Clock clock,
                             @Value("${nfv.incidents.point-radius-meters:50}") double pointRadiusMeters) {
        this.incidentLogic = incidentLogic;
        this.incidentRepository = incidentRepository;
        this.segmentMatching = segmentMatching;
        this.segmentQueries = segmentQueries;
        this.routeSectionRepository = routeSectionRepository;
        this.clock = clock;
        this.pointRadiusMeters = pointRadiusMeters;
    }

    /**
     * Creates the incident, or changes {@code previousIncidentId} (also its type). An ended report ends the previous
     * incident now ({@code validTo}), nothing is deleted.
     *
     * @param previousIncidentId incident of the previous version of the same event at the source, if any
     */
    @Transactional
    public ReportOutcome report(ReportedIncident report, @Nullable Long previousIncidentId) {
        Optional<Incident> previous = previousIncidentId == null ? Optional.empty()
                : incidentRepository.findById(previousIncidentId);
        if (report.ended()) {
            return previous.map(this::end).orElseGet(() -> ReportOutcome.none(ReportOutcome.Reason.ENDED));
        }
        if (report.location() instanceof IncidentLocation.Area) {
            return ReportOutcome.none(ReportOutcome.Reason.AREA_NOT_SUPPORTED);
        }
        Set<Long> segmentIds;
        try {
            segmentIds = locate(report);
        } catch (RoutingUnavailableException e) {
            return ReportOutcome.none(ReportOutcome.Reason.ROUTING_UNAVAILABLE);
        }
        if (segmentIds.isEmpty()) {
            return ReportOutcome.none(ReportOutcome.Reason.NO_SEGMENTS);
        }
        String description = report.description() == null || report.description().length() <= MAX_DESCRIPTION
                ? report.description() : report.description().substring(0, MAX_DESCRIPTION);
        OffsetDateTime validTo = report.validTo();
        if (validTo != null && report.type() == IncidentType.CLOSURE && report.windows().isEmpty()
                && validTo.isBefore(OffsetDateTime.now(clock))) {
            // still reported after its end: the date is out of date, not the closure
            validTo = null;
        } else if (validTo != null && validTo.isBefore(report.validFrom())) {
            validTo = report.validFrom();
        }
        IncidentDetails details = new IncidentDetails(report.type(), description, report.validFrom(), validTo,
                report.expectedDelay(), report.assumedDelay(), report.windows(), report.vehicleScope(),
                report.timesUnconfirmed());
        Incident incident = previous.isPresent()
                ? incidentLogic.change(previous.get().getId(), details, segmentIds)
                : incidentLogic.create(details, segmentIds);
        return ReportOutcome.incident(incident.getId());
    }

    /** Whether a planned route passes the region during {@code [from, to]}. */
    @Transactional(readOnly = true)
    public boolean hasPassagesInRegion(String region, OffsetDateTime from, OffsetDateTime to) {
        return segmentQueries.anyInRegion(routeSectionRepository.findSegmentIdsPassing(from, to), region);
    }

    /** Whether any of the incidents has not ended yet. */
    @Transactional(readOnly = true)
    public boolean anyActive(Collection<Long> incidentIds) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return incidentRepository.findAllById(incidentIds).stream()
                .anyMatch(incident -> incident.getValidTo() == null || incident.getValidTo().isAfter(now));
    }

    private ReportOutcome end(Incident incident) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (incident.getValidTo() == null || incident.getValidTo().isAfter(now)) {
            OffsetDateTime end = now.isBefore(incident.getValidFrom()) ? incident.getValidFrom() : now;
            IncidentDetails current = incident.details();
            incidentLogic.change(incident.getId(), new IncidentDetails(current.type(), current.description(),
                    current.validFrom(), end, current.expectedDelay(), current.assumedDelay(), current.windows(),
                    current.vehicleScope(), current.timesUnconfirmed()), Set.copyOf(incident.getSegmentIds()));
        }
        return ReportOutcome.incident(incident.getId());
    }

    private Set<Long> locate(ReportedIncident report) {
        Double bearing = report.direction() == null ? null : report.direction().bearing();
        Set<Long> segmentIds = new LinkedHashSet<>();
        if (report.location() == null) {
            return segmentIds;
        }
        switch (report.location()) {
            case IncidentLocation.Point point -> segmentIds.addAll(segmentMatching.matchPoint(report.networkRegion(),
                    geoPoint(point.coordinate()), pointRadiusMeters, report.roadRef(), bearing, report.linkOnly()));
            case IncidentLocation.Lines lines -> {
                for (List<IncidentLocation.Coordinate> line : lines.lines()) {
                    segmentIds.addAll(segmentMatching.matchLine(report.networkRegion(),
                            new GeoLine(line.stream().map(IncidentReporting::geoPoint).toList()), report.roadRef(),
                            bearing, report.linkOnly()));
                }
            }
            case IncidentLocation.Area _ -> {
                // not located yet, see report()
            }
        }
        return segmentIds;
    }

    private static GeoPoint geoPoint(IncidentLocation.Coordinate coordinate) {
        return new GeoPoint(coordinate.lat(), coordinate.lon());
    }
}
