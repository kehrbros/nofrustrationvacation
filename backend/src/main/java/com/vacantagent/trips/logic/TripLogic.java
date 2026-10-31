package com.vacantagent.trips.logic;

import com.vacantagent.trips.data.RouteRepository;
import com.vacantagent.trips.data.StopRepository;
import com.vacantagent.trips.data.TripCoverImageRepository;
import com.vacantagent.trips.data.TripRepository;
import com.vacantagent.trips.entities.CoverImageSource;
import com.vacantagent.trips.entities.Route;
import com.vacantagent.trips.entities.Stop;
import com.vacantagent.trips.entities.Trip;
import com.vacantagent.trips.entities.TripCoverImage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Trips, stops and title photos of a user. Every method checks that the trip belongs to the user. */
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
    private final TripCoverImageRepository coverImageRepository;
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

    /** Route id → trip id for all routes of the user's trips. */
    public Map<Long, Long> tripIdsByRouteId(long appUserId) {
        return routeRepository.findRouteAndTripIdsOfUser(appUserId).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    @Transactional
    public Trip create(long appUserId, String name, OffsetDateTime startAt, OffsetDateTime endAt) {
        String trimmed = requireName(name);
        if (startAt == null || endAt == null) {
            throw new InvalidTripInputException("start and end of the trip are required");
        }
        if (endAt.isBefore(startAt)) {
            throw new InvalidTripInputException("trip ends before it starts");
        }
        return tripRepository.save(new Trip(appUserId, trimmed, startAt, endAt, OffsetDateTime.now(clock)));
    }

    /**
     * Appends the stop to the trip. From the second stop on, a route from the previous stop is created, still without
     * sections (planning needs a routing service, see Docs/architecture.md).
     */
    @Transactional
    public Stop addStop(long appUserId, long tripId, NewStop newStop) {
        Trip trip = get(appUserId, tripId);
        String name = requireName(newStop.name());
        if ((newStop.latitude() == null) != (newStop.longitude() == null)) {
            throw new InvalidTripInputException("latitude and longitude only together");
        }
        if (newStop.latitude() != null && (Math.abs(newStop.latitude()) > 90 || Math.abs(newStop.longitude()) > 180)) {
            throw new InvalidTripInputException("coordinates out of range");
        }
        if (newStop.arrivalAt() != null && newStop.departureAt() != null
                && newStop.departureAt().isBefore(newStop.arrivalAt())) {
            throw new InvalidTripInputException("departure before arrival");
        }
        List<Stop> existing = stopRepository.findByTripIdOrderByPosition(tripId);
        Stop stop = stopRepository.save(new Stop(trip, existing.size(), name, newStop.latitude(), newStop.longitude(),
                newStop.arrivalAt(), newStop.departureAt()));
        if (!existing.isEmpty()) {
            routeRepository.save(new Route(trip, existing.getLast(), stop));
        }
        return stop;
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

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidTripInputException("name is required");
        }
        if (name.strip().length() > 255) {
            throw new InvalidTripInputException("name has at most 255 characters");
        }
        return name.strip();
    }
}
