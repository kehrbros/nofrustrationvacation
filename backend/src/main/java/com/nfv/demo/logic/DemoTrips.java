package com.nfv.demo.logic;

import com.nfv.bookings.entities.BookingSource;
import com.nfv.bookings.logic.BookingLogic;
import com.nfv.bookings.logic.NewAccommodationBooking;
import com.nfv.demo.logic.DemoScenario.DemoIncident;
import com.nfv.demo.logic.DemoScenario.DemoStay;
import com.nfv.demo.logic.DemoScenario.DemoStop;
import com.nfv.incidents.logic.IncidentLocation;
import com.nfv.incidents.logic.RoutePlacement;
import com.nfv.input.logic.TrafficEventInspection;
import com.nfv.input.logic.TrafficEventProcessor;
import com.nfv.input.logic.TrafficPolling;
import com.nfv.input.sources.manual.ManualSource;
import com.nfv.input.sources.manual.ManualTrafficEvent;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.logic.NewStop;
import com.nfv.trips.logic.NewWaypoint;
import com.nfv.trips.logic.TripEditing;
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Creates the demo trip for a user through the normal logic: trip, stops with planned routes, bookings, and the test
 * incidents through the manual test source, see Docs/demo-trip.md. Each call creates a new trip that started two days ago
 * (New Zealand), the user is under way; the incidents are shared by all demo trips of the same first day.
 */
@Service
@ConditionalOnProperty(name = "nfv.input.sources.manual.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DemoTrips {
    private static final Logger LOG = LoggerFactory.getLogger(DemoTrips.class);

    private final TripLogic tripLogic;
    private final TripEditing tripEditing;
    private final BookingLogic bookingLogic;
    private final RoutePlacement routePlacement;
    private final ManualSource manualSource;
    private final TrafficPolling polling;
    private final TrafficEventProcessor processor;
    private final TrafficEventInspection inspection;
    private final Clock clock;

    /**
     * @param placedOnRoute the incident was placed onto the planned route; otherwise it lies where the scenario says
     *                      (route not planned, e.g. no network yet)
     * @param outcome       {@code INCIDENT}, {@code PENDING} or why there is no incident, {@code null} if unknown
     */
    public record DemoIncidentResult(String key, boolean placedOnRoute, @Nullable String outcome) {
    }

    public record DemoTrip(long tripId, List<DemoIncidentResult> incidents) {
    }

    public DemoTrip create(long appUserId, DemoScenario.Language language) {
        OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        LocalDate firstDay = LocalDate.now(clock.withZone(DemoScenario.ZONE))
                .minusDays(DemoScenario.STARTED_DAYS_AGO);
        DemoScenario scenario = new DemoScenario(firstDay, language, now);

        Trip trip = tripLogic.create(appUserId, scenario.tripName(), scenario.startAt(), scenario.endAt(),
                DemoScenario.TRAVELERS);
        long tripId = trip.getId();
        for (DemoStop stop : scenario.stops()) {
            tripEditing.addStop(appUserId, tripId, new NewStop(stop.name(), stop.latitude(), stop.longitude(),
                    stop.arrivalAt(), stop.departureAt()), null);
        }
        List<Stop> stops = tripLogic.stops(appUserId, tripId);
        List<DemoStop> demoStops = scenario.stops();
        for (int i = 0; i < stops.size(); i++) {
            DemoStay stay = demoStops.get(i).stay();
            if (stay != null) {
                bookingLogic.createAccommodation(appUserId, stops.get(i).getId(), booking(stay));
            }
        }
        routeFrom(appUserId, tripId, stops, DemoScenario.CROWN_RANGE_ROUTE_FROM).ifPresent(route ->
                tripEditing.replaceWaypoints(appUserId, tripId, route.getId(), List.of(new NewWaypoint(null,
                        DemoScenario.CARDRONA_LATITUDE, DemoScenario.CARDRONA_LONGITUDE, "Cardrona", null))));

        List<DemoIncidentResult> results = new ArrayList<>();
        for (DemoIncident incident : scenario.incidents()) {
            results.add(report(appUserId, tripId, stops, firstDay, incident));
        }
        return new DemoTrip(tripId, results);
    }

    private DemoIncidentResult report(long appUserId, long tripId, List<Stop> stops, LocalDate firstDay,
                                      DemoIncident incident) {
        String id = "demo-" + incident.key() + "-" + firstDay;
        IncidentLocation.Coordinate target = new IncidentLocation.Coordinate(incident.latitude(), incident.longitude());
        Optional<RoutePlacement.Placement> placement = routeFrom(appUserId, tripId, stops, incident.fromStop())
                .flatMap(route -> routePlacement.place(appUserId, tripId, route.getId(), target));
        IncidentLocation.Coordinate point = placement.map(RoutePlacement.Placement::point).orElse(target);
        String roadRef = placement.map(RoutePlacement.Placement::roadRef).orElse(null);
        if (placement.isEmpty()) {
            LOG.warn("Demo incident {} not placed onto trip {}, reported as given", incident.key(), tripId);
        }
        post(event(id, incident, point, roadRef, false));
        if (incident.resolved()) {
            post(event(id, incident, point, roadRef, true));
        }
        String outcome = inspection.latest(ManualSource.NAME, id)
                .map(TrafficEventInspection.EventStatus::outcome).orElse(null);
        return new DemoIncidentResult(incident.key(), placement.isPresent(), outcome);
    }

    private void post(ManualTrafficEvent event) {
        manualSource.add(event);
        TrafficPolling.PollResult result = polling.pollNow(ManualSource.NAME);
        if (result.status() != TrafficPolling.Status.POLLED) {
            LOG.warn("Demo incident {}: source {} {}", event.id(), ManualSource.NAME, result.status());
        }
        processor.processPending();
    }

    private static ManualTrafficEvent event(String id, DemoIncident incident, IncidentLocation.Coordinate point,
                                            @Nullable String roadRef, boolean resolved) {
        return new ManualTrafficEvent(id, incident.type(), new ManualTrafficEvent.Position(point.lat(), point.lon()),
                null, roadRef, null, incident.validFrom(), incident.validTo(), incident.delayMinutes(), null,
                incident.text(), null, null, false, resolved, null);
    }

    private Optional<Route> routeFrom(long appUserId, long tripId, List<Stop> stops, int fromStop) {
        if (fromStop + 1 >= stops.size()) {
            return Optional.empty();
        }
        long fromId = stops.get(fromStop).getId();
        long toId = stops.get(fromStop + 1).getId();
        return tripLogic.routes(appUserId, tripId).stream()
                .filter(route -> route.getFromStop().getId() == fromId && route.getToStop().getId() == toId)
                .findFirst();
    }

    private static NewAccommodationBooking booking(DemoStay stay) {
        return new NewAccommodationBooking(BookingSource.MANUAL, stay.name(), stay.addressLine(), "NZ",
                stay.latitude(), stay.longitude(), null, null, null, null, "Demo", stay.reference(), null,
                stay.checkIn(), stay.checkOut(), DemoScenario.ZONE.getId(), stay.checkInFrom(), stay.checkInUntil(),
                stay.checkOutUntil(), DemoScenario.TRAVELERS, 1, stay.price(), DemoScenario.CURRENCY, null,
                stay.notes());
    }
}
