package com.nfv.trips.logic;

import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.entities.PlanOutcome;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.RouteSection;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

/** Sections and planning status of a {@link Route}, planned by {@link RoutePlanning}. */
@Service
@RequiredArgsConstructor
public class RouteLogic {
    private final RouteRepository routeRepository;
    private final RouteSectionRepository routeSectionRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * Replaces all sections of the route and publishes {@link RouteChanged}. Consequences of the old sections are
     * removed by the database ({@code ON DELETE CASCADE}) and found again for the new ones.
     */
    @Transactional
    public List<RouteSection> replaceSections(long routeId, List<PlannedSection> sections) {
        routeSectionRepository.deleteByRouteId(routeId);
        Route route = route(routeId);
        route.planned(OffsetDateTime.now(clock));
        List<RouteSection> saved = routeSectionRepository.saveAllAndFlush(IntStream.range(0, sections.size())
                .mapToObj(i -> {
                    PlannedSection section = sections.get(i);
                    return new RouteSection(route, i, section.segmentId(), section.fromFraction(),
                            section.toFraction(), section.expectedPassageStart(), section.expectedPassageEnd());
                })
                .toList());
        events.publishEvent(new RouteChanged(routeId));
        return saved;
    }

    /**
     * Removes the sections after a change of stops or waypoints, so that incidents no longer match the old way.
     * Consequences go with them ({@code ON DELETE CASCADE}). Publishes {@link RouteChanged}.
     */
    @Transactional
    public void discardSections(long routeId) {
        routeSectionRepository.deleteByRouteId(routeId);
        route(routeId).discarded();
        events.publishEvent(new RouteChanged(routeId));
    }

    /** Records a failed planning, the sections stay as they are. */
    @Transactional
    public void planFailed(long routeId, PlanOutcome outcome, @Nullable String reason) {
        route(routeId).planFailed(outcome, reason);
    }

    private Route route(long routeId) {
        return routeRepository.findById(routeId).orElseThrow(() -> new EntityNotFoundException("route " + routeId));
    }
}