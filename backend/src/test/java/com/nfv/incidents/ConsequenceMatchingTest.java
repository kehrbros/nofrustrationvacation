package com.nfv.incidents;

import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Consequence;
import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.Incident;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.entities.MatchKind;
import com.nfv.incidents.entities.VehicleScope;
import com.nfv.incidents.logic.IncidentDetails;
import com.nfv.incidents.logic.IncidentLogic;
import com.nfv.incidents.logic.IncidentReporting;
import com.nfv.incidents.logic.ReportedIncident;
import com.nfv.incidents.logic.RouteTooLate;
import com.nfv.incidents.logic.TimeWindow;
import com.nfv.incidents.logic.TripIncidents;
import com.nfv.network.data.ExtractVersionRepository;
import com.nfv.network.data.RoadSegmentRepository;
import com.nfv.network.entities.ExtractVersion;
import com.nfv.network.entities.RoadSegment;
import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.StopRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.RouteSection;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.logic.NewStop;
import com.nfv.trips.logic.PlannedSection;
import com.nfv.trips.logic.RouteLogic;
import com.nfv.trips.logic.TripLogic;
import com.nfv.user.logic.AppUserProvisioning;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Incidents and routes are matched both ways via their segments and the passage time, see Docs/domain.md. */
@SpringBootTest
@RecordApplicationEvents
class ConsequenceMatchingTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    private static final GeometryFactory GEOMETRY = new GeometryFactory(new PrecisionModel(), 4326);
    /** Target stop: changing a stop needs coordinates in New Zealand; the same ones keep the sections. */
    private static final double[] TARGET = {-41.2865, 174.7762};

    @Autowired
    private IncidentLogic incidentLogic;
    @Autowired
    private RouteLogic routeLogic;
    @Autowired
    private ConsequenceRepository consequenceRepository;
    @Autowired
    private ExtractVersionRepository extractVersionRepository;
    @Autowired
    private RoadSegmentRepository roadSegmentRepository;
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

    @Autowired
    private TripLogic tripLogic;
    @Autowired
    private TripIncidents tripIncidents;
    @Autowired
    private IncidentReporting incidentReporting;
    @Autowired
    private ApplicationEvents applicationEvents;

    private long first;
    private long second;
    private long elsewhere;
    private long routeId;
    private long userId;
    private long tripId;
    private long targetStopId;

    @BeforeEach
    void network() {
        ExtractVersion version = extractVersionRepository.save(new ExtractVersion("test", DEPARTURE.minusDays(1)));
        long way = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);
        first = segment(version, way, 0);
        second = segment(version, way, 1);
        elsewhere = segment(version, way, 2);
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
            assertThat(c.impact()).isEqualTo(Impact.BLOCKED);
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
                .satisfies(c -> assertThat(c.impact()).isEqualTo(Impact.DELAYED));

        plan(routeId, first, elsewhere);
        assertThat(consequenceRepository.findByRouteId(routeId)).isEmpty();
    }

    @Test
    void arrivalChangeFlipsBetweenDelayedAndTooLate() {
        // planned arrival DEPARTURE + 30 min, 20 min jam on the second segment → expected arrival + 50 min
        plan(routeId, first, second);
        Incident jam = inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau",
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(3), Duration.ofMinutes(20), Set.of(second)));
        Consequence delayed = consequenceRepository.findByIncidentId(jam.getId()).getFirst();
        assertThat(delayed.getCategory()).isEqualTo(Category.DELAYED);
        assertThat(delayed.getAssessedDelay()).isEqualTo(Duration.ofMinutes(20));
        assertThat(delayed.getExpectedArrival()).isEqualTo(DEPARTURE.plusMinutes(50));
        assertThat(delayed.getDeadline()).isEqualTo(DEPARTURE.plusHours(1));

        arriveAt(DEPARTURE.plusMinutes(40));
        Consequence late = consequenceRepository.findByIncidentId(jam.getId()).getFirst();
        assertThat(late.getId()).isEqualTo(delayed.getId());
        assertThat(late.getCategory()).isEqualTo(Category.TOO_LATE);
        assertThat(late.getDeadline()).isEqualTo(DEPARTURE.plusMinutes(40));
        assertThat(tripIncidents.routeSummaries(userId, tripId)).singleElement().satisfies(summary -> {
            assertThat(summary.worstCategory()).isEqualTo(Category.TOO_LATE);
            assertThat(summary.expectedArrival()).isEqualTo(DEPARTURE.plusMinutes(50));
            assertThat(summary.slack()).isEqualTo(Duration.ofMinutes(-10));
        });
        assertThat(tripIncidents.activeCountsByTrip(userId)).containsEntry(tripId, 1);

        arriveAt(DEPARTURE.plusHours(2));
        assertThat(consequenceRepository.findByIncidentId(jam.getId()).getFirst().getCategory())
                .isEqualTo(Category.DELAYED);

        arriveAt(null);
        assertThat(consequenceRepository.findByIncidentId(jam.getId()).getFirst().getDeadline()).isNull();
    }

    @Test
    void tooLatePublishesTheIncidentsAndTheDeadline() {
        plan(routeId, first, second);
        Incident jam = inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau",
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(3), Duration.ofMinutes(20), Set.of(second)));
        assertThat(applicationEvents.stream(RouteTooLate.class)).as("still in time").isEmpty();

        arriveAt(DEPARTURE.plusMinutes(40));

        assertThat(applicationEvents.stream(RouteTooLate.class)).singleElement().satisfies(event -> {
            assertThat(event.routeId()).isEqualTo(routeId);
            assertThat(event.incidentIds()).containsExactly(jam.getId());
            assertThat(event.deadline()).isEqualTo(DEPARTURE.plusMinutes(40));
        });
    }

    @Test
    void assessingAgainWithoutACategoryChangePublishesNothing() {
        plan(routeId, first, second);
        Incident jam = inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau",
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(3), Duration.ofMinutes(20), Set.of(second)));
        arriveAt(DEPARTURE.plusMinutes(40));
        assertThat(applicationEvents.stream(RouteTooLate.class)).hasSize(1);

        // another deadline, still too late
        arriveAt(DEPARTURE.plusMinutes(45));
        // another delay, still too late
        inTransaction(() -> incidentLogic.change(jam.getId(), jam.getDescription(), jam.getValidFrom(),
                jam.getValidTo(), Duration.ofMinutes(30), Set.of(second)));

        assertThat(consequenceRepository.findByIncidentId(jam.getId()).getFirst().getCategory())
                .isEqualTo(Category.TOO_LATE);
        assertThat(applicationEvents.stream(RouteTooLate.class)).hasSize(1);
    }

    @Test
    void onlyTheChangeToTooLatePublishes() {
        plan(routeId, first, second);
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau",
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(3), Duration.ofMinutes(20), Set.of(second)));

        arriveAt(DEPARTURE.plusMinutes(40));
        assertThat(applicationEvents.stream(RouteTooLate.class)).hasSize(1);
        arriveAt(DEPARTURE.plusHours(2));
        assertThat(applicationEvents.stream(RouteTooLate.class)).as("back to delayed").hasSize(1);
        arriveAt(DEPARTURE.plusMinutes(40));
        assertThat(applicationEvents.stream(RouteTooLate.class)).as("too late again").hasSize(2);
    }

    @Test
    void delayedAndNoImpactPublishNothing() {
        plan(routeId, first, second);
        inTransaction(() -> incidentLogic.create(IncidentType.OTHER, "Vorsicht",
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(3), Duration.ofMinutes(2), Set.of(first)));
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau",
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(3), Duration.ofMinutes(20), Set.of(second)));

        assertThat(consequenceRepository.findByRouteId(routeId)).extracting(Consequence::getCategory)
                .containsExactlyInAnyOrder(Category.NO_IMPACT, Category.DELAYED);
        assertThat(applicationEvents.stream(RouteTooLate.class)).isEmpty();
    }

    @Test
    void closureHitOnlyWithinTheTolerancePublishesNothing() {
        plan(routeId, first);
        // passage ends after 15 minutes, the tolerance is 15 minutes
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "Sperrung",
                DEPARTURE.plusMinutes(25), DEPARTURE.plusHours(5), null, Set.of(first)));

        assertThat(consequenceRepository.findByRouteId(routeId)).singleElement().satisfies(c -> {
            assertThat(c.getMatch()).isEqualTo(MatchKind.MARGINAL);
            assertThat(c.getCategory()).isEqualTo(Category.DELAYED);
        });
        assertThat(applicationEvents.stream(RouteTooLate.class)).isEmpty();
    }

    @Test
    void tooLateThatIsPassedPublishesNothing() {
        OffsetDateTime passage = OffsetDateTime.now(ZoneOffset.UTC).minusHours(5).withNano(0);
        planAt(passage, second);
        arriveAt(passage.plusMinutes(20));

        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau",
                passage.minusHours(1), passage.plusHours(6), Duration.ofMinutes(20), Set.of(second)));

        assertThat(consequenceRepository.findByRouteId(routeId)).singleElement()
                .satisfies(c -> assertThat(c.getCategory()).isEqualTo(Category.TOO_LATE));
        assertThat(applicationEvents.stream(RouteTooLate.class)).isEmpty();
    }

    @Test
    void passedIncidentsStayInTheEventOfACurrentOne() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        routeLogic.replaceSections(routeId, List.of(
                new PlannedSection(first, now.minusHours(5), now.minusHours(4)),
                new PlannedSection(second, now.plusHours(1), now.plusHours(2))));
        arriveAt(now.plusHours(2).plusMinutes(10));

        Incident passed = inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau",
                now.minusHours(6), now.plusHours(6), Duration.ofMinutes(20), Set.of(first)));
        assertThat(applicationEvents.stream(RouteTooLate.class)).as("only passed so far").isEmpty();
        Incident current = inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau",
                now.minusHours(6), now.plusHours(6), Duration.ofMinutes(20), Set.of(second)));

        assertThat(applicationEvents.stream(RouteTooLate.class)).singleElement().satisfies(event ->
                assertThat(event.incidentIds()).containsExactlyInAnyOrder(passed.getId(), current.getId()));
    }

    @Test
    void noImpactIsListedButNotCounted() {
        plan(routeId, first, second);
        Incident caution = inTransaction(() -> incidentLogic.create(IncidentType.OTHER, "Vorsicht",
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(3), Duration.ofMinutes(2), Set.of(first)));

        assertThat(consequenceRepository.findByIncidentId(caution.getId())).singleElement()
                .satisfies(c -> assertThat(c.getCategory()).isEqualTo(Category.NO_IMPACT));
        assertThat(tripIncidents.active(userId, tripId)).hasSize(1);
        assertThat(tripIncidents.activeCountsByTrip(userId)).doesNotContainKey(tripId);
    }

    @Test
    void passedPlaceIsNeitherListedNorCounted() {
        OffsetDateTime passage = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        planAt(passage, first);
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Stau", passage.minusHours(1),
                passage.plusDays(1), Duration.ofMinutes(20), Set.of(first)));

        assertThat(consequenceRepository.findByRouteId(routeId)).hasSize(1);
        assertThat(tripIncidents.active(userId, tripId)).isEmpty();
        assertThat(tripIncidents.activeCountsByTrip(userId)).doesNotContainKey(tripId);
    }

    @Test
    void waitStillRunningIsListed() {
        OffsetDateTime passage = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        planAt(passage, first);
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "Sperrung", passage.minusHours(1),
                passage.plusHours(3), null, Set.of(first)));

        assertThat(tripIncidents.active(userId, tripId)).hasSize(1);
    }

    @Test
    void impassableCountsAsWaitingMaxWait() {
        OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        planAt(recent, first);
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "Sperrung", recent.minusDays(2), null, null,
                Set.of(first)));
        assertThat(tripIncidents.active(userId, tripId)).hasSize(1);

        planAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(13), first);
        assertThat(consequenceRepository.findByRouteId(routeId)).hasSize(1);
        assertThat(tripIncidents.active(userId, tripId)).isEmpty();
    }

    @Test
    void scheduledIncidentEndedBeforeItsStartAffectsNothing() {
        plan(routeId, first);
        Incident scheduled = inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "Geplant",
                DEPARTURE.plusMinutes(5), DEPARTURE.plusHours(3), null, Set.of(first)));
        assertThat(consequenceRepository.findByIncidentId(scheduled.getId())).hasSize(1);

        incidentReporting.report(new ReportedIncident(IncidentType.CLOSURE, null, DEPARTURE.plusMinutes(5), null, null,
                null, null, null, null, "test", true, List.of(), null, false, false), scheduled.getId());

        assertThat(consequenceRepository.findByIncidentId(scheduled.getId())).isEmpty();
    }

    @Test
    void windowsCanChangeAgainAndAgainAndStayInTheHistory() {
        Incident incident = inTransaction(() -> incidentLogic.create(details(0, 40), Set.of(first)));

        // overlapping sets of windows in another offset than the database hands back: partly the same, partly new
        for (int shift = 1; shift <= 25; shift++) {
            int from = shift;
            inTransaction(() -> incidentLogic.change(incident.getId(), details(from, 40), Set.of(first)));
        }
        // nothing changes
        inTransaction(() -> incidentLogic.change(incident.getId(), details(25, 40), Set.of(first)));
        // fewer, a subset
        inTransaction(() -> incidentLogic.change(incident.getId(), details(30, 5), Set.of(first)));

        inTransaction(() -> {
            var reader = AuditReaderFactory.get(entityManager);
            List<Number> revisions = reader.getRevisions(Incident.class, incident.getId());
            assertThat(reader.find(Incident.class, incident.getId(), revisions.getLast()).timeWindows())
                    .extracting(window -> window.startsAt().toInstant())
                    .containsExactlyElementsOf(details(30, 5).windows().stream()
                            .map(window -> window.startsAt().toInstant()).toList());
            assertThat(reader.find(Incident.class, incident.getId(), revisions.getFirst()).timeWindows()).hasSize(40);
            return null;
        });
    }

    /** {@code count} nights, one per day, starting {@code first} days after {@link #DEPARTURE}. */
    private static IncidentDetails details(int first, int count) {
        List<TimeWindow> windows = new ArrayList<>();
        OffsetDateTime start = DEPARTURE.withOffsetSameInstant(ZoneOffset.ofHours(13));
        for (int i = first; i < first + count; i++) {
            windows.add(new TimeWindow(start.plusDays(i), start.plusDays(i).plusHours(7)));
        }
        return new IncidentDetails(IncidentType.CLOSURE, null, DEPARTURE.minusDays(1), DEPARTURE.plusDays(90), null, null,
                windows, VehicleScope.ALL, false);
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

    /** Segment number {@code index} of a straight test road, 0.01 degrees each. */
    private long segment(ExtractVersion version, long way, int index) {
        int fromLon = 1_740_000_000 + index * 100_000;
        int toLon = fromLon + 100_000;
        LineString geometry = GEOMETRY.createLineString(new Coordinate[]{
                new Coordinate(fromLon / 1e7, -41.0), new Coordinate(toLon / 1e7, -41.0)});
        return roadSegmentRepository.save(new RoadSegment("test", way, -410_000_000, fromLon, -410_000_000, toLon,
                index, null, "Test Road", "PRIMARY", false, false, 840, geometry, version)).getId();
    }

    private long newRoute() {
        return inTransaction(() -> {
            userId = appUserProvisioning.findOrCreate("trip-" + UUID.randomUUID(), null, null).getId();
            Trip trip = tripRepository.save(new Trip(userId, "Urlaub", DEPARTURE, DEPARTURE.plusDays(7), DEPARTURE.minusDays(2)));
            tripId = trip.getId();
            Stop start = stopRepository.save(new Stop(trip, 0, "Heilbronn", null, null, null, DEPARTURE));
            Stop end = stopRepository.save(new Stop(trip, 1, "Kupferzell", TARGET[0], TARGET[1], DEPARTURE.plusHours(1),
                    null));
            targetStopId = end.getId();
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
    }

    private void arriveAt(OffsetDateTime arrival) {
        inTransaction(() -> tripLogic.updateStop(userId, tripId, targetStopId,
                new NewStop("Kupferzell", TARGET[0], TARGET[1], arrival, null)));
    }

    /** One section of 15 minutes starting at {@code passage}. */
    private void planAt(OffsetDateTime passage, long segmentId) {
        routeLogic.replaceSections(routeId, List.of(new PlannedSection(segmentId, passage, passage.plusMinutes(15))));
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
