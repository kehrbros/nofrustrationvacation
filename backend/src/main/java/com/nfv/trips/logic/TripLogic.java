package com.nfv.trips.logic;

import com.nfv.network.logic.Regions;
import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.data.StopRepository;
import com.nfv.trips.data.TripCoverImageRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.data.WaypointRepository;
import com.nfv.trips.entities.CoverImageSource;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.entities.TripCoverImage;
import com.nfv.trips.entities.Waypoint;
import com.nfv.trips.entities.WaypointOrigin;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Trips, stops, waypoints and title photos of a user. Every method checks that the trip belongs to the user. Changes
 * of stops and waypoints return the routes to plan; planning runs after the commit, see {@link TripEditing}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripLogic {
    /** Largest title photo accepted for upload. */
    public static final int MAX_COVER_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> COVER_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final TripRepository tripRepository;
    private final StopRepository stopRepository;
    private final RouteRepository routeRepository;
    private final RouteSectionRepository routeSectionRepository;
    private final WaypointRepository waypointRepository;
    private final TripCoverImageRepository coverImageRepository;
    private final RouteLogic routeLogic;
    private final Regions regions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** All trips of the user, ascending by start. Grouping into current, upcoming and past is up to the client. */
    public List<TripOverview> list(long appUserId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Trip> trips = tripRepository.findByAppUserIdOrderByStartAtAscIdAsc(appUserId);
        if (trips.isEmpty()) {
            return List.of();
        }
        List<Long> tripIds = trips.stream().map(Trip::getId).toList();
        Map<Long, List<Stop>> stops = stopRepository.findByTripIdInOrderByTripIdAscPositionAsc(tripIds).stream()
                .collect(Collectors.groupingBy(stop -> stop.getTrip().getId(), LinkedHashMap::new, Collectors.toList()));
        Set<Long> withCover = new HashSet<>(tripRepository.findTripIdsWithCoverImage(tripIds));
        return trips.stream().map(trip -> {
            List<Stop> tripStops = stops.getOrDefault(trip.getId(), List.of());
            Stop next = tripStops.stream()
                    .filter(stop -> stop.getArrivalAt() != null && stop.getArrivalAt().isAfter(now))
                    .findFirst().orElse(null);
            return new TripOverview(trip, trip.phaseAt(now), tripStops.size(), next, withCover.contains(trip.getId()));
        }).toList();
    }

    public Trip get(long appUserId, long tripId) {
        return tripRepository.findByIdAndAppUserId(tripId, appUserId).orElseThrow(() -> new TripNotFoundException(tripId));
    }

    public List<Stop> stops(long appUserId, long tripId) {
        get(appUserId, tripId);
        return stopRepository.findByTripIdOrderByPosition(tripId);
    }

    public List<Route> routes(long appUserId, long tripId) {
        get(appUserId, tripId);
        return routeRepository.findByTripId(tripId);
    }

    /** A stop of one of the user's trips, e.g. to attach a booking. Empty for unknown and foreign stops. */
    public Optional<Stop> stop(long appUserId, long stopId) {
        return stopRepository.findById(stopId).filter(stop -> stop.getTrip().getAppUserId() == appUserId);
    }

    /** The route of the trip, {@link TripItemNotFoundException} if it belongs to another trip. */
    public Route route(long appUserId, long tripId, long routeId) {
        get(appUserId, tripId);
        return routeRepository.findById(routeId).filter(route -> route.getTrip().getId() == tripId)
                .orElseThrow(() -> new TripItemNotFoundException("route", routeId));
    }

    public List<Waypoint> waypoints(long appUserId, long tripId, long routeId) {
        route(appUserId, tripId, routeId);
        return waypointRepository.findByRouteIdOrderByPosition(routeId);
    }

    /** Route id → trip id for all routes of the user's trips. */
    public Map<Long, Long> tripIdsByRouteId(long appUserId) {
        return routeRepository.findRouteAndTripIdsOfUser(appUserId).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    @Transactional
    public Trip create(long appUserId, String name, OffsetDateTime startAt, OffsetDateTime endAt,
                       @Nullable Integer travelers) {
        String trimmed = requireName(name);
        requirePeriod(startAt, endAt);
        requireTravelers(travelers);
        Trip trip = new Trip(appUserId, trimmed, startAt, endAt, OffsetDateTime.now(clock));
        trip.changeTravelers(travelers);
        return tripRepository.save(trip);
    }

    /** Name, period and travelers, nothing to plan: passage times depend on the departures at the stops only. */
    @Transactional
    public Trip update(long appUserId, long tripId, String name, OffsetDateTime startAt, OffsetDateTime endAt,
                       @Nullable Integer travelers) {
        Trip trip = get(appUserId, tripId);
        String trimmed = requireName(name);
        requirePeriod(startAt, endAt);
        requireTravelers(travelers);
        trip.rename(trimmed);
        trip.reschedule(startAt, endAt);
        trip.changeTravelers(travelers);
        return trip;
    }

    /**
     * Deletes the trip with its routes, waypoints and stops; the bookings at the stops via {@link StopsDeleting}, title
     * photo and consequences by the database.
     */
    @Transactional
    public void delete(long appUserId, long tripId) {
        Trip trip = get(appUserId, tripId);
        List<Stop> stops = stopRepository.findByTripIdOrderByPosition(tripId);
        if (!stops.isEmpty()) {
            events.publishEvent(new StopsDeleting(tripId, stops.stream().map(Stop::getId).toList(), true));
        }
        deleteRoutes(routeRepository.findByTripId(tripId));
        stopRepository.deleteAll(stops);
        tripRepository.delete(trip);
    }

    /** Inserts the stop at the position ({@code null}: at the end) and connects it to its neighbours by routes. */
    @Transactional
    public TripChange addStop(long appUserId, long tripId, NewStop newStop, @Nullable Integer position) {
        Trip trip = get(appUserId, tripId);
        String name = validate(newStop);
        List<Stop> stops = new ArrayList<>(stopRepository.findByTripIdOrderByPosition(tripId));
        int index = position == null ? stops.size() : position;
        if (index < 0 || index > stops.size()) {
            throw new InvalidTripInputException("position must be 0 to " + stops.size());
        }
        stops.add(index, stopRepository.save(new Stop(trip, index, name, newStop.latitude(), newStop.longitude(),
                newStop.arrivalAt(), newStop.departureAt())));
        return new TripChange(tripId, syncRoutes(trip, stops));
    }

    /**
     * Changes the stop. New coordinates discard the sections of the routes to and from it, a new departure those of
     * the route from it. The arrival does not affect planning, it publishes {@link StopArrivalChanged}.
     */
    @Transactional
    public TripChange updateStop(long appUserId, long tripId, long stopId, NewStop changed) {
        get(appUserId, tripId);
        Stop stop = stopRepository.findById(stopId).filter(s -> s.getTrip().getId() == tripId)
                .orElseThrow(() -> new TripItemNotFoundException("stop", stopId));
        String name = validate(changed);
        boolean relocated = !Objects.equals(stop.getLatitude(), changed.latitude())
                || !Objects.equals(stop.getLongitude(), changed.longitude());
        boolean departs = !sameInstant(stop.getDepartureAt(), changed.departureAt());
        boolean arrives = !sameInstant(stop.getArrivalAt(), changed.arrivalAt());
        stop.rename(name);
        stop.relocate(changed.latitude(), changed.longitude());
        stop.reschedule(changed.arrivalAt(), changed.departureAt());
        Set<Long> affected = new LinkedHashSet<>();
        Set<Long> arrivingRoutes = new LinkedHashSet<>();
        for (Route route : routeRepository.findByTripId(tripId)) {
            boolean from = route.getFromStop().getId() == stopId;
            boolean to = route.getToStop().getId() == stopId;
            if (relocated && (from || to) || departs && from) {
                affected.add(route.getId());
            }
            if (to) {
                arrivingRoutes.add(route.getId());
            }
        }
        affected.forEach(routeLogic::discardSections);
        if (arrives && !arrivingRoutes.isEmpty()) {
            // the deadline of the routes to the stop changed: incidents assesses them again
            stopRepository.flush();
            events.publishEvent(new StopArrivalChanged(stopId, arrivingRoutes));
        }
        return new TripChange(tripId, affected);
    }

    /**
     * The stop follows a new accommodation in another place ({@link StayPlaces#isOtherPlace}): new coordinates, the
     * name of the new place, and the waypoints from suggestions of the routes to and from it are dropped, they lead to
     * the old place (waypoints of the user stay). The sections of these routes are discarded. Within the same place
     * nothing changes; a stop without coordinates gets those of the accommodation and keeps its name.
     *
     * @param place name of the new place (town), {@code null} or blank: the name stays
     * @return the routes to plan, none within the same place
     * @throws TripItemNotFoundException unknown or foreign stop
     */
    @Transactional
    public TripChange moveStopToStay(long appUserId, long stopId, double latitude, double longitude,
                                     @Nullable String place) {
        Stop stop = stop(appUserId, stopId).orElseThrow(() -> new TripItemNotFoundException("stop", stopId));
        long tripId = stop.getTrip().getId();
        if (!StayPlaces.isOtherPlace(stop.getLatitude(), stop.getLongitude(), latitude, longitude)) {
            return new TripChange(tripId, Set.of());
        }
        requireCoordinates(latitude, longitude);
        boolean located = stop.getLatitude() != null;
        stop.relocate(latitude, longitude);
        // a stop without coordinates only gets them, its name is the user's
        if (located && place != null && !place.isBlank() && place.strip().length() <= 255) {
            stop.rename(place.strip());
        }
        Set<Long> affected = new LinkedHashSet<>();
        for (Route route : routeRepository.findByTripId(tripId)) {
            if (route.getFromStop().getId() == stopId || route.getToStop().getId() == stopId) {
                waypointRepository.deleteAll(waypointRepository.findByRouteIdOrderByPosition(route.getId()).stream()
                        .filter(waypoint -> waypoint.getOrigin() == WaypointOrigin.SUGGESTION).toList());
                routeLogic.discardSections(route.getId());
                affected.add(route.getId());
            }
        }
        return new TripChange(tripId, affected);
    }

    /** Moves the stop to the position (0 to number of stops - 1), the stops in between shift by one. */
    @Transactional
    public TripChange moveStop(long appUserId, long tripId, long stopId, int position) {
        Trip trip = get(appUserId, tripId);
        List<Stop> stops = new ArrayList<>(stopRepository.findByTripIdOrderByPosition(tripId));
        Stop stop = find(stops, stopId);
        if (position < 0 || position >= stops.size()) {
            throw new InvalidTripInputException("position must be 0 to " + (stops.size() - 1));
        }
        stops.remove(stop);
        stops.add(position, stop);
        return new TripChange(tripId, syncRoutes(trip, stops));
    }

    /** Deletes the stop with the routes to and from it; its neighbours get a new route. */
    @Transactional
    public TripChange removeStop(long appUserId, long tripId, long stopId) {
        Trip trip = get(appUserId, tripId);
        List<Stop> stops = new ArrayList<>(stopRepository.findByTripIdOrderByPosition(tripId));
        Stop stop = find(stops, stopId);
        events.publishEvent(new StopsDeleting(tripId, List.of(stopId), false));

        stops.remove(stop);
        Set<Long> created = syncRoutes(trip, stops);
        stopRepository.delete(stop);
        return new TripChange(tripId, created);
    }

    /**
     * Replaces the waypoints of the route by the ordered list: with id changed, without id created, missing ones
     * deleted. Discards the sections of the route.
     */
    @Transactional
    public TripChange replaceWaypoints(long appUserId, long tripId, long routeId, List<NewWaypoint> waypoints) {
        Route route = route(appUserId, tripId, routeId);
        Map<Long, Waypoint> existing = waypointRepository.findByRouteIdOrderByPosition(routeId).stream()
                .collect(Collectors.toMap(Waypoint::getId, Function.identity()));
        Set<Long> listed = new HashSet<>();
        for (NewWaypoint waypoint : waypoints) {
            validate(waypoint);
            if (waypoint.id() != null) {
                if (!existing.containsKey(waypoint.id())) {
                    throw new TripItemNotFoundException("waypoint", waypoint.id());
                }
                if (!listed.add(waypoint.id())) {
                    throw new InvalidTripInputException("waypoint " + waypoint.id() + " listed twice");
                }
            }
        }
        waypointRepository.deleteAll(existing.values().stream().filter(w -> !listed.contains(w.getId())).toList());
        // positions are unique per route and Hibernate deletes last: park the kept ones on free positions first
        int parked = -1;
        for (long id : listed) {
            existing.get(id).moveTo(parked--);
        }
        waypointRepository.flush();
        for (int i = 0; i < waypoints.size(); i++) {
            NewWaypoint waypoint = waypoints.get(i);
            String name = strip(waypoint.name());
            String roadHint = strip(waypoint.roadHint());
            if (waypoint.id() == null) {
                waypointRepository.save(new Waypoint(route, i, waypoint.latitude(), waypoint.longitude(), name,
                        roadHint, waypoint.origin()));
            } else {
                Waypoint kept = existing.get(waypoint.id());
                kept.change(waypoint.latitude(), waypoint.longitude(), name, roadHint);
                kept.moveTo(i);
            }
        }
        routeLogic.discardSections(routeId);
        return new TripChange(tripId, Set.of(routeId));
    }

    public Optional<TripCoverImage> coverImage(long appUserId, long tripId) {
        get(appUserId, tripId);
        return coverImageRepository.findByTripId(tripId);
    }

    /** Sets the title photo uploaded by the user, replacing a generated or earlier one. */
    @Transactional
    public TripCoverImage uploadCoverImage(long appUserId, long tripId, String contentType, byte[] content) {
        get(appUserId, tripId);
        if (!COVER_IMAGE_TYPES.contains(contentType)) {
            throw new InvalidTripInputException("title photo must be JPEG, PNG or WebP");
        }
        if (content.length == 0 || content.length > MAX_COVER_IMAGE_BYTES) {
            throw new InvalidTripInputException("title photo must have 1 byte to 5 MB");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<TripCoverImage> existing = coverImageRepository.findByTripId(tripId);
        if (existing.isPresent()) {
            existing.get().replace(CoverImageSource.UPLOADED, contentType, content, now);
            return existing.get();
        }
        return coverImageRepository.save(new TripCoverImage(tripId, CoverImageSource.UPLOADED, contentType, content, now));
    }

    /** Sets a generated title photo; keeps a photo the user has uploaded meanwhile. */
    @Transactional
    public void setGeneratedCoverImage(long appUserId, long tripId, String contentType, byte[] content) {
        get(appUserId, tripId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<TripCoverImage> existing = coverImageRepository.findByTripId(tripId);
        if (existing.isEmpty()) {
            coverImageRepository.save(new TripCoverImage(tripId, CoverImageSource.GENERATED, contentType, content, now));
        } else if (existing.get().getSource() == CoverImageSource.GENERATED) {
            existing.get().replace(CoverImageSource.GENERATED, contentType, content, now);
        }
    }

    /**
     * Renumbers the stops in the given order and connects neighbours by routes: routes of unchanged pairs stay (with
     * their waypoints and sections), the others are deleted. Returns the ids of the new routes.
     */
    private Set<Long> syncRoutes(Trip trip, List<Stop> stops) {
        for (int i = 0; i < stops.size(); i++) {
            stops.get(i).moveTo(i);
        }
        Map<List<Long>, Route> stale = new HashMap<>();
        routeRepository.findByTripId(trip.getId()).forEach(route ->
                stale.put(List.of(route.getFromStop().getId(), route.getToStop().getId()), route));
        Set<Long> created = new LinkedHashSet<>();
        for (int i = 1; i < stops.size(); i++) {
            Stop from = stops.get(i - 1);
            Stop to = stops.get(i);
            if (stale.remove(List.of(from.getId(), to.getId())) == null) {
                created.add(routeRepository.save(new Route(trip, from, to)).getId());
            }
        }
        deleteRoutes(stale.values());
        return created;
    }

    /** Sections in bulk (not historized, consequences cascade), waypoints and routes per entity for Envers. */
    private void deleteRoutes(Collection<Route> routes) {
        for (Route route : routes) {
            routeSectionRepository.deleteByRouteId(route.getId());
            waypointRepository.deleteAll(waypointRepository.findByRouteIdOrderByPosition(route.getId()));
            routeRepository.delete(route);
        }
    }

    private static Stop find(List<Stop> stops, long stopId) {
        return stops.stream().filter(stop -> stop.getId() == stopId).findFirst()
                .orElseThrow(() -> new TripItemNotFoundException("stop", stopId));
    }

    /** Checks the stop, returns its stripped name. */
    private String validate(NewStop stop) {
        String name = requireName(stop.name());
        if (stop.latitude() == null || stop.longitude() == null) {
            throw new InvalidTripInputException("latitude and longitude are required");
        }
        requireCoordinates(stop.latitude(), stop.longitude());
        if (stop.arrivalAt() != null && stop.departureAt() != null && stop.departureAt().isBefore(stop.arrivalAt())) {
            throw new InvalidTripInputException("departure before arrival");
        }
        return name;
    }

    private void validate(NewWaypoint waypoint) {
        requireCoordinates(waypoint.latitude(), waypoint.longitude());
        if (waypoint.name() != null && waypoint.name().strip().length() > 255
                || waypoint.roadHint() != null && waypoint.roadHint().strip().length() > 255) {
            throw new InvalidTripInputException("name and road hint have at most 255 characters");
        }
    }

    /** Valid coordinates within a region of the road network, see Docs/domain.md#trips. */
    private void requireCoordinates(double latitude, double longitude) {
        if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new InvalidTripInputException("coordinates out of range");
        }
        if (!regions.covers(latitude, longitude)) {
            throw new LocationOutsideRegionsException();
        }
    }

    private static void requirePeriod(OffsetDateTime startAt, OffsetDateTime endAt) {
        if (startAt == null || endAt == null) {
            throw new InvalidTripInputException("start and end of the trip are required");
        }
        if (endAt.isBefore(startAt)) {
            throw new InvalidTripInputException("trip ends before it starts");
        }
    }

    private static void requireTravelers(@Nullable Integer travelers) {
        if (travelers != null && (travelers < 1 || travelers > 99)) {
            throw new InvalidTripInputException("travelers must be between 1 and 99");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidTripInputException("name is required");
        }
        if (name.strip().length() > 255) {
            throw new InvalidTripInputException("name has at most 255 characters");
        }
        return name.strip();
    }

    private static @Nullable String strip(@Nullable String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** Same point in time, the offset does not matter for planning. */
    private static boolean sameInstant(@Nullable OffsetDateTime a, @Nullable OffsetDateTime b) {
        return a == null ? b == null : b != null && a.isEqual(b);
    }
}