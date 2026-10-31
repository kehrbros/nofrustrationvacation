package com.vacantagent.trips.logic;

import com.vacantagent.trips.data.RouteRepository;
import com.vacantagent.trips.data.RouteSectionRepository;
import com.vacantagent.trips.entities.Route;
import com.vacantagent.trips.entities.RouteSection;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

/** Plans the way of a {@link Route}. Where the sections come from (routing service) is still open. */
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
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("route " + routeId));
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
}
