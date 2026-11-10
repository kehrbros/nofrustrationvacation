package com.nfv.bookings.logic;

import com.nfv.bookings.data.AccommodationDetailsRepository;
import com.nfv.bookings.data.BookingRepository;
import com.nfv.bookings.entities.AccommodationDetails;
import com.nfv.bookings.entities.Booking;
import com.nfv.bookings.entities.BookingSource;
import com.nfv.bookings.entities.BookingStatus;
import com.nfv.trips.logic.BookingFit;
import com.nfv.trips.logic.StopBookingsConflictException;
import com.nfv.trips.logic.StopRescheduling;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Checks the bookings at a stop against its new days, in the transaction of the change ({@link StopRescheduling}).
 * Cancelled bookings do not count. Bookings outside refuse the change, or with {@link BookingFit#TRIM_BOOKINGS} are
 * shortened to the new days, only in the app. Bookings made in the app (LiteAPI, {@link BookingSource#SEARCH}) are
 * never shortened: the booking at the hotel stays as it is.
 */
@Component
@RequiredArgsConstructor
class StopReschedulingListener {
    private final BookingRepository bookingRepository;
    private final AccommodationDetailsRepository accommodationRepository;

    @EventListener
    public void onStopRescheduling(StopRescheduling event) {
        List<Booking> bookings = bookingRepository.findByStopId(event.stopId()).stream()
                .filter(booking -> booking.getStatus() != BookingStatus.CANCELLED)
                .filter(booking -> StayFit.outside(stay(booking), event.arrivalAt(), event.departureAt()))
                .toList();
        if (bookings.isEmpty()) {
            return;
        }
        Map<Long, ZoneId> zones = accommodationRepository.findByBookingIdIn(bookings.stream().map(Booking::getId)
                        .toList()).stream()
                .collect(Collectors.toMap(details -> details.getBooking().getId(),
                        (AccommodationDetails details) -> ZoneId.of(details.getTimeZone()), (a, b) -> a));
        Map<Long, Optional<StayFit.Stay>> trimmed = bookings.stream().collect(Collectors.toMap(Booking::getId,
                booking -> booking.getSource() == BookingSource.SEARCH ? Optional.empty()
                        : StayFit.trim(stay(booking), zones.get(booking.getId()), event.arrivalAt(),
                        event.departureAt()), (a, b) -> a));
        if (event.fit() == BookingFit.TRIM_BOOKINGS && trimmed.values().stream().allMatch(Optional::isPresent)) {
            bookings.forEach(booking -> {
                StayFit.Stay stay = trimmed.get(booking.getId()).orElseThrow();
                booking.reschedule(stay.startsAt(), stay.endsAt());
            });
            return;
        }
        List<StopBookingsConflictException.OutsideBooking> outside = new ArrayList<>();
        for (Booking booking : bookings) {
            outside.add(new StopBookingsConflictException.OutsideBooking(booking.getId(), booking.getTitle(),
                    booking.getStartsAt(), booking.getEndsAt(), trimmed.get(booking.getId()).isPresent()));
        }
        throw new StopBookingsConflictException(outside,
                extended(event.arrivalAt(), bookings.stream().map(Booking::getStartsAt), true),
                extended(event.departureAt(), bookings.stream().map(Booking::getEndsAt), false));
    }

    /** Arrival on the earliest check-in day or departure on the latest check-out day, if that is beyond it. */
    private static @Nullable OffsetDateTime extended(@Nullable OffsetDateTime time,
                                                     java.util.stream.Stream<OffsetDateTime> stays, boolean earlier) {
        if (time == null) {
            return null;
        }
        Comparator<LocalDate> order = earlier ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Optional<LocalDate> day = stays.filter(Objects::nonNull).map(OffsetDateTime::toLocalDate).min(order);
        LocalDate current = time.toLocalDate();
        return day.filter(candidate -> earlier ? candidate.isBefore(current) : candidate.isAfter(current))
                .map(time::with).orElse(null);
    }

    private static StayFit.Stay stay(Booking booking) {
        return new StayFit.Stay(booking.getStartsAt(), booking.getEndsAt());
    }
}
