package com.nfv.bookings.logic;

import com.nfv.bookings.entities.BookingReservation;
import com.nfv.bookings.entities.ReservationHotel;
import com.nfv.incidents.entities.Impact;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.RoutingUnavailableException;
import com.nfv.recommendations.logic.SuggestedOption;
import com.nfv.recommendations.logic.SuggestionResult;
import com.nfv.recommendations.logic.Suggestions;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.logic.StayPlaces;
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Checks the way to the hotel of a reservation before it is paid: if the hotel lies in another place than the stop,
 * the stop moves there after booking, so the route to it changes. Computed like a route suggestion (incidents
 * included) and not stored; only a hint, booking stays possible. See
 * Docs/accommodation-alternatives.md#route-vor-dem-buchen-pruefen.
 */
@Service
@RequiredArgsConstructor
public class StayRouteCheck {
    private final ReservationSteps steps;
    private final TripLogic tripLogic;
    private final Suggestions suggestions;
    private final TimeZones timeZones;
    private final Clock clock;

    /** Why the way could not be checked. */
    public enum Problem {
        /** No route leads to the stop, it is the first one. */
        FIRST_STOP,
        /** The departure to the stop is over, the planned arrival says nothing anymore. */
        UNDER_WAY,
        MISSING_COORDINATES,
        MISSING_DEPARTURE,
        NO_ROUTE,
        ROUTING_UNAVAILABLE
    }

    /**
     * @param otherPlace     the hotel lies in another place, the stop moves there after booking; all other values are
     *                       only set then
     * @param distanceMeters from the stop to the hotel
     * @param routeId        route to the stop
     * @param arrivalAt      arrival at the hotel on the best way, including delays of incidents
     * @param checkInUntil   latest check-in at the hotel, {@code null} if unknown
     * @param late           arrival after the latest check-in
     * @param blocked        even the best way passes a closure
     * @param delay          delay by incidents on the best way
     */
    public record Result(boolean otherPlace, int distanceMeters, @Nullable Long routeId,
                         @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime checkInUntil, boolean late,
                         boolean blocked, Duration delay, @Nullable Problem problem) {

        static Result samePlace(int distanceMeters) {
            return new Result(false, distanceMeters, null, null, null, false, false, Duration.ZERO, null);
        }

        static Result unchecked(int distanceMeters, @Nullable Long routeId, @Nullable OffsetDateTime checkInUntil,
                                Problem problem) {
            return new Result(true, distanceMeters, routeId, null, checkInUntil, false, false, Duration.ZERO,
                    problem);
        }
    }

    /** @throws ReservationNotFoundException unknown or foreign reservation */
    public Result check(long appUserId, long reservationId) {
        BookingReservation reservation = steps.owned(appUserId, reservationId);
        Stop stop = tripLogic.stop(appUserId, reservation.getStopId())
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        ReservationHotel hotel = reservation.getHotel();
        if (hotel.latitude() == null || hotel.longitude() == null || stop.getLatitude() == null
                || stop.getLongitude() == null) {
            // without coordinates of the stop there is no route to change
            return Result.samePlace(0);
        }
        int distance = StayPlaces.distanceMeters(stop.getLatitude(), stop.getLongitude(), hotel.latitude(),
                hotel.longitude());
        if (!StayPlaces.isOtherPlace(stop.getLatitude(), stop.getLongitude(), hotel.latitude(), hotel.longitude())) {
            return Result.samePlace(distance);
        }
        OffsetDateTime checkInUntil = checkInUntil(reservation, hotel);
        Optional<Route> arriving = tripLogic.routes(appUserId, stop.getTrip().getId()).stream()
                .filter(route -> route.getToStop().getId() == stop.getId())
                .findFirst();
        if (arriving.isEmpty()) {
            return Result.unchecked(distance, null, checkInUntil, Problem.FIRST_STOP);
        }
        long routeId = arriving.get().getId();
        long fromStopId = arriving.get().getFromStop().getId();
        OffsetDateTime departure = tripLogic.stops(appUserId, stop.getTrip().getId()).stream()
                .filter(s -> s.getId() == fromStopId).findFirst().map(Stop::getDepartureAt).orElse(null);
        if (departure != null && departure.isBefore(OffsetDateTime.now(clock))) {
            return Result.unchecked(distance, routeId, checkInUntil, Problem.UNDER_WAY);
        }
        SuggestionResult result;
        try {
            result = suggestions.towards(appUserId, routeId, new GeoPoint(hotel.latitude(), hotel.longitude()));
        } catch (RoutingUnavailableException e) {
            return Result.unchecked(distance, routeId, checkInUntil, Problem.ROUTING_UNAVAILABLE);
        }
        if (result.problem() != null || result.options().isEmpty()) {
            Problem problem = result.problem() == null ? Problem.NO_ROUTE : Problem.valueOf(result.problem().name());
            return Result.unchecked(distance, routeId, checkInUntil, problem);
        }
        SuggestedOption best = result.options().getFirst();
        boolean blocked = best.conflicts().stream().anyMatch(c -> c.impact() == Impact.BLOCKED);
        boolean late = checkInUntil != null && best.arrivalAt().isAfter(checkInUntil);
        return new Result(true, distance, routeId, best.arrivalAt(), checkInUntil, late, blocked, best.delay(), null);
    }

    /** Latest check-in on the day of arrival in the hotel's time zone; check-in until midnight counts as unknown. */
    private @Nullable OffsetDateTime checkInUntil(BookingReservation reservation, ReservationHotel hotel) {
        if (hotel.checkInUntil() == null || hotel.checkInUntil().equals(LocalTime.MIDNIGHT)) {
            return null;
        }
        ZoneId zone = timeZones.of(hotel.latitude(), hotel.longitude(), null);
        return reservation.getCheckIn().atTime(hotel.checkInUntil()).atZone(zone).toOffsetDateTime();
    }
}
