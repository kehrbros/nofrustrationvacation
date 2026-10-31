package com.vacantagent.incidents;

import com.vacantagent.incidents.data.ConsequenceRepository;
import com.vacantagent.incidents.entities.Consequence;
import com.vacantagent.incidents.entities.Impact;
import com.vacantagent.incidents.entities.Incident;
import com.vacantagent.incidents.entities.IncidentType;
import com.vacantagent.incidents.logic.IncidentLogic;
import com.vacantagent.network.data.RoadRepository;
import com.vacantagent.network.data.SegmentRepository;
import com.vacantagent.network.entities.Road;
import com.vacantagent.network.entities.Segment;
import com.vacantagent.trips.data.RouteRepository;
import com.vacantagent.trips.data.StopRepository;
import com.vacantagent.trips.data.TripRepository;
import com.vacantagent.trips.entities.Route;
import com.vacantagent.trips.entities.RouteSection;
import com.vacantagent.trips.entities.Stop;
import com.vacantagent.trips.entities.Trip;
import com.vacantagent.trips.logic.PlannedSection;
import com.vacantagent.trips.logic.RouteLogic;
import com.vacantagent.user.logic.AppUserProvisioning;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Incidents and routes are matched both ways via their segments and the passage time, see Docs/domain.md. */
@SpringBootTest
class ConsequenceMatchingTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);

    @Autowired
    private IncidentLogic incidentLogic;
    @Autowired
    private RouteLogic routeLogic;
    @Autowired
    private ConsequenceRepository consequenceRepository;
    @Autowired
    private RoadRepository roadRepository;
    @Autowired
    private SegmentRepository segmentRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private StopRepository stopRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private EntityManager entityManager;

    private long first;
    private long second;
    private long elsewhere;
    private long routeId;

    @BeforeEach
    void network() {
        Road road = roadRepository.save(new Road("A" + UUID.randomUUID().toString().substring(0, 8), "Test"));
        first = segment(road, "Heilbronn", "Weinsberg");
        second = segment(road, "Weinsberg", "Öhringen");
        elsewhere = segment(road, "Öhringen", "Kupferzell");
        routeId = newRoute();
    }

    @Test
    void incidentDuringThePassageAffectsTheRoute() {
        plan(routeId, first, second);

        Incident incident = inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "Vollsperrung",
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(3), null, Set.of(second)));

        List<Consequence> consequences = consequenceRepository.findByIncidentId(incident.getId());
        assertThat(consequences).singleElement().satisfies(c -> {
            assertThat(c.getRouteId()).isEqualTo(routeId);
            assertThat(c.getImpact()).isEqualTo(Impact.BLOCKED);
            assertThat(c.getRouteSectionId()).isEqualTo(sectionOn(routeId, second));
        });
    }

    @Test
    void incidentBeforeOrOnOtherSegmentsDoesNotAffectTheRoute() {
        plan(routeId, first, second);

        Incident over = inTransaction(() -> incidentLogic.create(IncidentType.ROADWORKS, null,
                DEPARTURE.minusDays(1), DEPARTURE.minusHours(1), null, Set.of(first)));
        Incident apart = inTransaction(() -> incidentLogic.create(IncidentType.ROADWORKS, null,
                DEPARTURE, null, null, Set.of(elsewhere)));

        assertThat(consequenceRepository.findByIncidentId(over.getId())).isEmpty();
        assertThat(consequenceRepository.findByIncidentId(apart.getId())).isEmpty();
    }

    @Test
    void changedIncidentUpdatesOrRemovesItsConsequence() {
        plan(routeId, first, second);
        Incident incident = inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau",
                DEPARTURE, null, Duration.ofMinutes(20), Set.of(first, second)));
        Consequence before = consequenceRepository.findByIncidentId(incident.getId()).getFirst();
        assertThat(before.getRouteSectionId()).isEqualTo(sectionOn(routeId, first));

        inTransaction(() -> incidentLogic.change(incident.getId(), "Stau", DEPARTURE, null, Duration.ofMinutes(45),
                Set.of(second)));
        Consequence after = consequenceRepository.findByIncidentId(incident.getId()).getFirst();
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getRouteSectionId()).isEqualTo(sectionOn(routeId, second));
        assertThat(after.getDelay()).isEqualTo(Duration.ofMinutes(45));

        inTransaction(() -> incidentLogic.change(incident.getId(), "Stau", DEPARTURE, null, null, Set.of(elsewhere)));
        assertThat(consequenceRepository.findByIncidentId(incident.getId())).isEmpty();
    }

    @Test
    void newOrChangedRouteIsCheckedAgainstActiveIncidents() {
        Incident incident = inTransaction(() -> incidentLogic.create(IncidentType.ACCIDENT, null,
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(1), null, Set.of(second)));
        assertThat(consequenceRepository.findByIncidentId(incident.getId())).isEmpty();

        plan(routeId, first, second);
        assertThat(consequenceRepository.findByRouteId(routeId)).singleElement()
                .satisfies(c -> assertThat(c.getImpact()).isEqualTo(Impact.DELAYED));

        plan(routeId, first, elsewhere);
        assertThat(consequenceRepository.findByRouteId(routeId)).isEmpty();
    }

    @Test
    void incidentHistoryIncludesItsSegments() {
        Incident incident = inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, null,
                DEPARTURE, null, null, Set.of(first)));
        inTransaction(() -> incidentLogic.change(incident.getId(), null, DEPARTURE, null, null, Set.of(second)));

        inTransaction(() -> {
            var reader = AuditReaderFactory.get(entityManager);
            List<Number> revisions = reader.getRevisions(Incident.class, incident.getId());
            assertThat(revisions).hasSize(2);
            assertThat(reader.find(Incident.class, incident.getId(), revisions.getFirst()).getSegmentIds())
                    .containsExactly(first);
            return null;
        });
    }

    private long segment(Road road, String from, String to) {
        return segmentRepository.save(new Segment(road, from, to,
                "{\"type\":\"LineString\",\"coordinates\":[[9.2,49.1],[9.3,49.1]]}")).getId();
    }

    private long newRoute() {
        return inTransaction(() -> {
            long userId = appUserProvisioning.findOrCreate("trip-" + UUID.randomUUID(), null, null).getId();
            Trip trip = tripRepository.save(new Trip(userId, "Urlaub", DEPARTURE, DEPARTURE.plusDays(7), DEPARTURE.minusDays(2)));
            Stop start = stopRepository.save(new Stop(trip, 0, "Heilbronn", null, null, null, DEPARTURE));
            Stop end = stopRepository.save(new Stop(trip, 1, "Kupferzell", null, null, DEPARTURE.plusHours(1), null));
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
    }

    /** Passes each segment in 15 minutes, starting at {@link #DEPARTURE}. */
    private void plan(long routeId, long... segmentIds) {
        List<PlannedSection> sections = new ArrayList<>();
        for (int i = 0; i < segmentIds.length; i++) {
            sections.add(new PlannedSection(segmentIds[i], DEPARTURE.plusMinutes(15L * i),
                    DEPARTURE.plusMinutes(15L * (i + 1))));
        }
        routeLogic.replaceSections(routeId, sections);
    }

    private long sectionOn(long routeId, long segmentId) {
        return inTransaction(() -> entityManager.createQuery(
                        "SELECT s FROM RouteSection s WHERE s.route.id = :route AND s.segmentId = :segment",
                        RouteSection.class)
                .setParameter("route", routeId).setParameter("segment", segmentId)
                .getSingleResult().getId());
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}
