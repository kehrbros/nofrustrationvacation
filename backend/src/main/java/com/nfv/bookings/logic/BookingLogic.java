package com.nfv.bookings.logic;

import com.nfv.bookings.data.AccommodationDetailsRepository;
import com.nfv.bookings.data.BookingRepository;
import com.nfv.bookings.entities.AccommodationDetails;
import com.nfv.bookings.entities.Booking;
import com.nfv.bookings.entities.BookingSource;
import com.nfv.bookings.entities.BookingStatus;
import com.nfv.bookings.entities.BookingType;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.logic.BookingFit;
import com.nfv.trips.logic.TripChange;
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bookings at the stops of a user's trips. Ownership is checked through the stop ({@link TripLogic#stop}). Check-in
 * and check-out of accommodations are stored in the time zone of the place, see Docs/domain.md#bookings.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingLogic {
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    private final BookingRepository bookingRepository;
    private final AccommodationDetailsRepository accommodationRepository;
    private final TripLogic tripLogic;
    private final TimeZones timeZones;
    private final BookingTodos todos;

    /** All bookings at the stops of the trip, ascending by start. */
    public List<BookingView> list(long appUserId, long tripId) {
        List<Long> stopIds = tripLogic.stops(appUserId, tripId).stream().map(Stop::getId).toList();
        if (stopIds.isEmpty()) {
            return List.of();
        }
        List<Booking> bookings = bookingRepository.findByStopIdInOrderByStartsAtAscIdAsc(stopIds);
        Map<Long, AccommodationDetails> details = bookings.isEmpty() ? Map.of()
                : accommodationRepository.findByBookingIdIn(bookings.stream().map(Booking::getId).toList()).stream()
                .collect(Collectors.toMap(detail -> detail.getBooking().getId(), Function.identity()));
        return bookings.stream().map(booking -> new BookingView(booking, details.get(booking.getId()))).toList();
    }

    @Transactional
    public BookingView createAccommodation(long appUserId, long stopId, NewAccommodationBooking input) {
        tripLogic.stop(appUserId, stopId).orElseThrow(() -> BookingNotFoundException.stop(stopId));
        Valid valid = validate(input);
        Booking booking = bookingRepository.save(
                new Booking(stopId, BookingType.ACCOMMODATION, input.source(), valid.content()));
        AccommodationDetails details = accommodationRepository.save(
                new AccommodationDetails(booking, valid.place(), valid.rules()));
        return new BookingView(booking, details);
    }

    /**
     * Replaces the content of an accommodation booking, source and status stay. Check-in and check-out must lie
     * within the days of the stop (Docs/domain.md#bookings), otherwise {@code resolution} decides: extend
     * the stop (in the same transaction, the routes are planned by the caller) or shorten the booking.
     *
     * @param resolution {@code null}: refuse
     * @throws BookingOutsideStopException outside the days of the stop and not resolved
     */
    @Transactional
    public ChangedBooking changeAccommodation(long appUserId, long bookingId, NewAccommodationBooking input,
                                              @Nullable StopConflict resolution) {
        Booking booking = owned(appUserId, bookingId);
        if (booking.getType() != BookingType.ACCOMMODATION) {
            throw new InvalidBookingInputException("booking is no accommodation");
        }
        Valid valid = validate(input);
        Stop stop = tripLogic.stop(appUserId, booking.getStopId())
                .orElseThrow(() -> BookingNotFoundException.booking(bookingId));
        Booking.BookingContent content = valid.content();
        StayFit.Stay stay = new StayFit.Stay(content.startsAt(), content.endsAt());
        OffsetDateTime arrivalAt = stop.getArrivalAt();
        OffsetDateTime departureAt = stop.getDepartureAt();
        boolean extend = false;
        if (booking.getStatus() != BookingStatus.CANCELLED && StayFit.outside(stay, arrivalAt, departureAt)) {
            Optional<StayFit.Stay> trimmed = booking.getSource() == BookingSource.SEARCH ? Optional.empty()
                    : StayFit.trim(stay, valid.place().timeZone(), arrivalAt, departureAt);
            OffsetDateTime extendedArrival = StayFit.startsTooEarly(stay, arrivalAt)
                    ? arrivalAt.with(stay.startsAt().toLocalDate()) : null;
            OffsetDateTime extendedDeparture = StayFit.endsTooLate(stay, departureAt)
                    ? departureAt.with(stay.endsAt().toLocalDate()) : null;
            if (resolution == StopConflict.TRIM_BOOKING && trimmed.isPresent()) {
                content = withStay(content, trimmed.get());
            } else if (resolution == StopConflict.EXTEND_STOP) {
                extend = true;
                arrivalAt = extendedArrival == null ? arrivalAt : extendedArrival;
                departureAt = extendedDeparture == null ? departureAt : extendedDeparture;
            } else {
                throw new BookingOutsideStopException(stop.getArrivalAt(), stop.getDepartureAt(), extendedArrival,
                        extendedDeparture, trimmed.isPresent());
            }
        }
        booking.change(content);
        AccommodationDetails details = accommodationRepository.findByBookingId(bookingId)
                .map(existing -> {
                    existing.change(valid.place(), valid.rules());
                    return existing;
                })
                .orElseGet(() -> accommodationRepository.save(new AccommodationDetails(booking, valid.place(), valid.rules())));
        TripChange change = new TripChange(stop.getTrip().getId(), Set.of());
        if (extend) {
            // the other bookings lay within the old days, so they fit the longer stay as well
            bookingRepository.flush();
            change = tripLogic.rescheduleStop(appUserId, stop.getId(), arrivalAt, departureAt, BookingFit.CHECK);
        }
        return new ChangedBooking(new BookingView(booking, details), change);
    }

    /** Changed booking and the routes to plan after commit if its stop was extended. */
    public record ChangedBooking(BookingView view, TripChange change) {
    }

    private static Booking.BookingContent withStay(Booking.BookingContent content, StayFit.Stay stay) {
        return new Booking.BookingContent(content.title(), content.provider(), content.reference(),
                content.manageUrl(), stay.startsAt(), stay.endsAt(), content.price(), content.currency(),
                content.cancellationDeadline(), content.notes());
    }

    /** An accommodation booking of the user with its details. */
    public BookingView accommodation(long appUserId, long bookingId) {
        Booking booking = owned(appUserId, bookingId);
        AccommodationDetails details = accommodationRepository.findByBookingId(bookingId).orElse(null);
        if (booking.getType() != BookingType.ACCOMMODATION || details == null) {
            throw new InvalidBookingInputException("booking is no accommodation");
        }
        return new BookingView(booking, details);
    }

    /** Marks the booking as cancelled in the app, e.g. after booking an alternative; the provider is not contacted. */
    @Transactional
    public BookingView cancel(long appUserId, long bookingId) {
        Booking booking = owned(appUserId, bookingId);
        booking.cancel();
        todos.cancelled(bookingId);

        return new BookingView(booking, accommodationRepository.findByBookingId(bookingId).orElse(null));
    }

    @Transactional
    public void delete(long appUserId, long bookingId) {
        Booking booking = owned(appUserId, bookingId);
        tripLogic.stop(appUserId, booking.getStopId())
                .ifPresent(stop -> todos.bookingRemoved(stop.getTrip().getId(), booking));
        accommodationRepository.findByBookingId(bookingId).ifPresent(accommodationRepository::delete);
        bookingRepository.delete(booking);
    }

    private Booking owned(long appUserId, long bookingId) {
        return bookingRepository.findById(bookingId)
                .filter(booking -> tripLogic.stop(appUserId, booking.getStopId()).isPresent())
                .orElseThrow(() -> BookingNotFoundException.booking(bookingId));
    }

    private Valid validate(NewAccommodationBooking input) {
        String name = text(input.name(), 255, "name");
        if (name == null) {
            throw new InvalidBookingInputException("name", "name of the accommodation is required");
        }
        if ((input.latitude() == null) != (input.longitude() == null)) {
            throw new InvalidBookingInputException("latitude and longitude only together");
        }
        if (input.latitude() != null && (Math.abs(input.latitude()) > 90 || Math.abs(input.longitude()) > 180)) {
            throw new InvalidBookingInputException("coordinates out of range");
        }
        if (input.checkIn() == null || input.checkOut() == null) {
            throw new InvalidBookingInputException(input.checkIn() == null ? "checkIn" : "checkOut",
                    "check-in and check-out are required");
        }
        if (!input.checkOut().isAfter(input.checkIn())) {
            throw new InvalidBookingInputException("checkOut", "check-out must be after check-in");
        }
        String countryCode = upper(input.countryCode());
        if (countryCode != null && !COUNTRY.matcher(countryCode).matches()) {
            throw new InvalidBookingInputException("countryCode", "country code must be ISO 3166-1 alpha-2");
        }
        String currency = upper(input.currency());
        if (input.price() != null) {
            if (input.price().signum() < 0 || input.price().compareTo(MAX_PRICE) > 0 || input.price().scale() > 2) {
                throw new InvalidBookingInputException("price", "price must be 0 or more with at most 2 decimals");
            }
            if (currency == null || !CURRENCY.matcher(currency).matches()) {
                throw new InvalidBookingInputException("currency", "price needs an ISO 4217 currency");
            }
        } else {
            currency = null;
        }
        Integer guests = count(input.guests(), "guests");
        Integer rooms = count(input.rooms(), "rooms");

        ZoneId zone = timeZones.of(input.latitude(), input.longitude(), input.fallbackTimeZone());
        AccommodationDetails.Place place = new AccommodationDetails.Place(name, text(input.addressLine(), 500, "addressLine"),
                countryCode, input.latitude(), input.longitude(), zone, text(input.phone(), 64, "phone"),
                text(input.email(), 255, "email"), url(input.website(), "website"), text(input.placeRef(), 255, "placeRef"));
        AccommodationDetails.HouseRules rules = new AccommodationDetails.HouseRules(input.checkInFrom(),
                input.checkInUntil(), input.checkOutUntil(), guests, rooms);
        Booking.BookingContent content = new Booking.BookingContent(name, text(input.provider(), 255, "provider"),
                text(input.reference(), 255, "reference"), url(input.manageUrl(), "manageUrl"),
                at(input.checkIn(), zone), at(input.checkOut(), zone), input.price(), currency,
                input.cancellationDeadline() == null ? null : at(input.cancellationDeadline(), zone),
                text(input.notes(), 2000, "notes"));
        return new Valid(content, place, rules);
    }

    /** Local time at the place with the offset valid there at that moment (summer time). */
    private static OffsetDateTime at(LocalDateTime local, ZoneId zone) {
        return local.atZone(zone).toOffsetDateTime();
    }

    private static @Nullable String text(@Nullable String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new InvalidBookingInputException(field, field + " has at most " + maxLength + " characters");
        }
        return stripped;
    }

    private static @Nullable String upper(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    /** Only http(s), the frontend renders it as link. */
    private static @Nullable String url(@Nullable String value, String field) {
        String url = text(value, 1000, field);
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null) {
                return url;
            }
        } catch (URISyntaxException e) {
            // falls through
        }
        throw new InvalidBookingInputException(field, field + " must be an http(s) URL");
    }

    private static @Nullable Integer count(@Nullable Integer value, String field) {
        if (value != null && (value < 1 || value > 99)) {
            throw new InvalidBookingInputException(field, field + " must be 1 to 99");
        }
        return value;
    }

    private record Valid(Booking.BookingContent content, AccommodationDetails.Place place,
                         AccommodationDetails.HouseRules rules) {
    }
}
