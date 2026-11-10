package com.nfv.bookings.api;

import com.nfv.bookings.dto.AlternativeCandidateDto;
import com.nfv.bookings.dto.AlternativeCriteriaDto;
import com.nfv.bookings.dto.AlternativeCriteriaInputDto;
import com.nfv.bookings.dto.AlternativeSearchResultDto;
import com.nfv.bookings.dto.AlternativesStatusDto;
import com.nfv.bookings.dto.BookingDto;
import com.nfv.bookings.dto.ReservationDto;
import com.nfv.bookings.dto.ReservationInputDto;
import com.nfv.bookings.dto.StayRouteCheckDto;
import com.nfv.bookings.entities.BookingReservation;
import com.nfv.bookings.logic.AlternativeCandidate;
import com.nfv.bookings.logic.AlternativeCriteria;
import com.nfv.bookings.logic.AlternativeReservations;
import com.nfv.bookings.logic.AlternativeSearch;
import com.nfv.bookings.logic.BookingLogic;
import com.nfv.bookings.logic.CriteriaProposal;
import com.nfv.bookings.logic.InvalidBookingInputException;
import com.nfv.bookings.logic.ReservationView;
import com.nfv.bookings.logic.StayRouteCheck;
import com.nfv.paypal.PayPalCheckout;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@Tag(name = "Alternative accommodations", description = "Search an alternative to an accommodation booking (LiteAPI, "
        + "ranked by the assistant) and book it, paid with PayPal")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AlternativeController {
    private final AlternativeSearch search;
    private final AlternativeReservations reservations;
    private final BookingLogic bookingLogic;
    private final PayPalCheckout checkout;
    private final StayRouteCheck stayRouteCheck;

    @Operation(summary = "Alternatives status", description = "Whether alternatives can be searched, booked, ranked.")
    @GetMapping("/bookings/alternatives/status")
    public AlternativesStatusDto alternativesStatus() {
        AlternativeSearch.Status status = search.status();
        return new AlternativesStatusDto(status.searchAvailable(), status.bookingAvailable(), status.rankingAvailable(),
                checkout.clientId(), checkout.environment());
    }

    @Operation(summary = "Criteria from a booking",
            description = "Use case: the booking cannot be used. Place, dates, guests, rooms and price of the booking.")
    @GetMapping("/accommodation-bookings/{bookingId}/alternative-criteria")
    public AlternativeCriteriaDto alternativeCriteria(@AuthenticationPrincipal CurrentUser user,
                                                      @PathVariable long bookingId) {
        CriteriaProposal proposal = search.criteriaFor(user.appUserId(), bookingId);
        return new AlternativeCriteriaDto(proposal.latitude(), proposal.longitude(), proposal.placeName(),
                proposal.radiusMeters(), proposal.checkIn(), proposal.checkOut(), proposal.guests(), proposal.rooms(),
                proposal.maxPrice(), proposal.currency(), proposal.excludeHotelId(), proposal.minStars(),
                proposal.missing());
    }

    @Operation(summary = "Search alternatives",
            description = "Offers from LiteAPI that meet all criteria. If available, the assistant leaves out those "
                    + "not comparable to the current accommodation and ranks the rest. Takes some seconds. 503 "
                    + "ALTERNATIVES_UNAVAILABLE, 429 ALTERNATIVES_RATE_LIMITED.")
    @PostMapping("/accommodation-bookings/{bookingId}/alternative-searches")
    public AlternativeSearchResultDto searchAlternatives(@AuthenticationPrincipal CurrentUser user,
                                                         @PathVariable long bookingId,
                                                         @RequestBody AlternativeCriteriaInputDto criteria) {
        AlternativeSearch.SearchResult result = search.search(user.appUserId(), bookingId, toCriteria(criteria));
        return new AlternativeSearchResultDto(result.candidates().stream().map(AlternativeController::toDto).toList(),
                result.recommendedHotelId(), result.ranked(), result.offersChecked(), result.excluded());
    }

    @Operation(summary = "Reserve an alternative",
            description = "Locks price and availability (prebook) and creates the PayPal payment. Send the user to "
                    + "approveUrl, then call /booking-reservations/{id}/booking. 409 OFFER_UNAVAILABLE (search "
                    + "again), 409 PRICE_EXCEEDED, 503 PAYPAL_NOT_CONFIGURED.")
    @PostMapping("/accommodation-bookings/{bookingId}/alternative-reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationDto reserveAlternative(@AuthenticationPrincipal CurrentUser user, @PathVariable long bookingId,
                                  @RequestBody ReservationInputDto input) {
        return toDto(reservations.reserve(user.appUserId(), bookingId, new AlternativeReservations.NewReservation(
                input.hotelId(), input.offerId(), input.checkIn(), input.checkOut(), number(input.guests(), "guests"),
                number(input.rooms(), "rooms"), input.maxPrice(), upper(input.currency()), input.firstName(),
                input.lastName(), input.email(), input.phone())));
    }

    @Operation(summary = "Reserve an accommodation at a stop",
            description = "Like an alternative, for a stop without accommodation to replace, e.g. a night on the way "
                    + "taken from a proposal of the agent. The booking is created at the stop. Same errors.")
    @PostMapping("/stops/{stopId}/stay-reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationDto reserveAtStop(@AuthenticationPrincipal CurrentUser user, @PathVariable long stopId,
                                        @RequestBody ReservationInputDto input) {
        return toDto(reservations.reserveAtStop(user.appUserId(), stopId, new AlternativeReservations.NewReservation(
                input.hotelId(), input.offerId(), input.checkIn(), input.checkOut(), number(input.guests(), "guests"),
                number(input.rooms(), "rooms"), input.maxPrice(), upper(input.currency()), input.firstName(),
                input.lastName(), input.email(), input.phone())));
    }

    @Operation(summary = "A reservation")

    @GetMapping("/booking-reservations/{reservationId}")
    public ReservationDto getReservation(@AuthenticationPrincipal CurrentUser user,
                                         @PathVariable long reservationId) {
        return toDto(reservations.get(user.appUserId(), reservationId));
    }

    @Operation(summary = "Check the way to the hotel of a reservation",
            description = "Before paying: if the hotel lies in another place than the stop, the arrival on the best "
                    + "way to it (incidents included) and whether it is after the latest check-in. Not stored.")
    @GetMapping("/booking-reservations/{reservationId}/route-check")
    public StayRouteCheckDto checkReservationRoute(@AuthenticationPrincipal CurrentUser user,
                                                   @PathVariable long reservationId) {
        StayRouteCheck.Result result = stayRouteCheck.check(user.appUserId(), reservationId);
        return new StayRouteCheckDto(result.otherPlace(), result.distanceMeters(), result.routeId(),
                result.arrivalAt(), result.checkInUntil(), result.late(), result.blocked(),
                result.delay().toSeconds(), result.problem());
    }

    @Operation(summary = "Book a reservation after the payment was approved",
            description = "Authorizes the payment, books at LiteAPI, captures the payment and adds the booking to the "
                    + "stop. Safe to repeat. 422 PAYMENT_NOT_APPROVED/PAYMENT_DECLINED, 409 OFFER_UNAVAILABLE (the "
                    + "payment is released), 503 ALTERNATIVES_UNAVAILABLE (try again).")
    @PostMapping("/booking-reservations/{reservationId}/booking")
    public ReservationDto bookReservation(@AuthenticationPrincipal CurrentUser user,
                                          @PathVariable long reservationId) {
        return toDto(reservations.complete(user.appUserId(), reservationId));
    }

    @Operation(summary = "Abort a reservation", description = "E.g. after cancelling at PayPal; nothing was paid.")
    @PostMapping("/booking-reservations/{reservationId}/abort")
    public ReservationDto abortReservation(@AuthenticationPrincipal CurrentUser user,
                                           @PathVariable long reservationId) {
        return toDto(reservations.abort(user.appUserId(), reservationId));
    }

    @Operation(summary = "Unfinished reservations of a trip",
            description = "Payment running (approved at PayPal, booking in progress) or to be checked "
                    + "(NEEDS_ATTENTION); the app shows them so nobody wonders what happened to the money.")
    @GetMapping("/trips/{tripId}/booking-reservations")
    public List<ReservationDto> unfinishedReservations(@AuthenticationPrincipal CurrentUser user,
                                                       @PathVariable long tripId) {
        return reservations.unfinished(user.appUserId(), tripId).stream().map(AlternativeController::toDto).toList();
    }

    @Operation(summary = "Cancel a booking made in the app and refund it",
            description = "Cancels at LiteAPI and refunds the PayPal payment (in full while free of charge). Only "
                    + "for alternatives booked in the app. 409 CANCELLATION_NOT_POSSIBLE, 409 OFFER_UNAVAILABLE "
                    + "(LiteAPI refused), 503 ALTERNATIVES_UNAVAILABLE.")
    @PostMapping("/bookings/{bookingId}/refund-cancellation")
    public ReservationDto cancelWithRefund(@AuthenticationPrincipal CurrentUser user, @PathVariable long bookingId) {
        return toDto(reservations.cancel(user.appUserId(), bookingId));
    }

    @Operation(summary = "Mark a booking as cancelled",
            description = "Only in the app, e.g. the booking an alternative replaces; the provider is not contacted.")
    @PostMapping("/bookings/{bookingId}/cancellation")
    public BookingDto cancelBooking(@AuthenticationPrincipal CurrentUser user, @PathVariable long bookingId) {
        return BookingController.toDto(bookingLogic.cancel(user.appUserId(), bookingId));
    }

    private static AlternativeCriteria toCriteria(AlternativeCriteriaInputDto dto) {
        if (dto.latitude() == null || dto.longitude() == null) {
            throw new InvalidBookingInputException("place of the search is required");
        }
        if (dto.checkIn() == null || dto.checkOut() == null) {
            throw new InvalidBookingInputException("check-in and check-out are required");
        }
        return new AlternativeCriteria(dto.latitude(), dto.longitude(), number(dto.radiusMeters(), "radius"),
                dto.checkIn(), dto.checkOut(), number(dto.guests(), "guests"), number(dto.rooms(), "rooms"),
                dto.maxPrice(), upper(dto.currency()), dto.excludeHotelId(), dto.minStars());
    }

    private static int number(@Nullable Integer value, String field) {
        if (value == null) {
            throw new InvalidBookingInputException(field + " is required");
        }
        return value;
    }

    private static @Nullable String upper(@Nullable String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static AlternativeCandidateDto toDto(AlternativeCandidate candidate) {
        return new AlternativeCandidateDto(candidate.hotelId(), candidate.name(), candidate.address(),
                candidate.latitude(), candidate.longitude(), candidate.distanceMeters(), candidate.stars(),
                candidate.rating(), candidate.photoUrl(), candidate.offerId(), candidate.roomName(),
                candidate.boardName(), candidate.refundable(), candidate.freeCancellationUntil(), candidate.price(),
                candidate.currency(), candidate.possiblySame(), candidate.reason());
    }

    private static ReservationDto toDto(ReservationView view) {
        BookingReservation reservation = view.reservation();
        return new ReservationDto(reservation.getId(), view.tripId(), reservation.getStopId(),
                reservation.getReplacesBookingId(), reservation.getBookingId(), reservation.getStatus(),
                reservation.getHotel().name(), reservation.getHotel().address(), reservation.getCheckIn(),
                reservation.getCheckOut(), reservation.getGuests(), reservation.getRooms(), reservation.getPrice(),
                reservation.getFee(), reservation.getCurrency(), reservation.isRefundable(),
                reservation.getFreeCancellationUntil(), view.approveUrl(), reservation.getPayPalOrderId(),
                reservation.getFailure(), reservation.getRefundAmount(), reservation.getPayPalRefundId() != null,
                view.replannedRouteIds(),
                view.booking() == null ? null : BookingController.toDto(view.booking()));
    }
}
