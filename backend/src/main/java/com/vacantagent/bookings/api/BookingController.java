package com.vacantagent.bookings.api;

import com.vacantagent.bookings.dto.AccommodationBookingInputDto;
import com.vacantagent.bookings.dto.AccommodationDraftDto;
import com.vacantagent.bookings.dto.AccommodationDto;
import com.vacantagent.bookings.dto.AssistantStatusDto;
import com.vacantagent.bookings.dto.BookingDto;
import com.vacantagent.bookings.entities.AccommodationDetails;
import com.vacantagent.bookings.entities.Booking;
import com.vacantagent.bookings.entities.BookingSource;
import com.vacantagent.bookings.logic.AccommodationDraft;
import com.vacantagent.bookings.logic.AccommodationExtractor;
import com.vacantagent.bookings.logic.BookingLogic;
import com.vacantagent.bookings.logic.BookingView;
import com.vacantagent.bookings.logic.NewAccommodationBooking;
import com.vacantagent.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Tag(name = "Bookings", description = "Bookings at the stops of the authenticated user's trips, first accommodations")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BookingController {
    private final BookingLogic bookingLogic;
    private final AccommodationExtractor extractor;

    @Operation(summary = "Bookings of a trip", description = "All bookings at the stops of the trip, ascending by start.")
    @GetMapping("/trips/{tripId}/bookings")
    public List<BookingDto> listBookings(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId) {
        return bookingLogic.list(user.appUserId(), tripId).stream().map(BookingController::toDto).toList();
    }

    @Operation(summary = "Add an accommodation booking to a stop")
    @PostMapping("/stops/{stopId}/accommodation-bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingDto createAccommodationBooking(@AuthenticationPrincipal CurrentUser user, @PathVariable long stopId,
                                                 @RequestBody AccommodationBookingInputDto booking) {
        return toDto(bookingLogic.createAccommodation(user.appUserId(), stopId, toInput(booking)));
    }

    @Operation(summary = "Replace an accommodation booking", description = "Source and status stay.")
    @PutMapping("/accommodation-bookings/{bookingId}")
    public BookingDto changeAccommodationBooking(@AuthenticationPrincipal CurrentUser user,
                                                 @PathVariable long bookingId,
                                                 @RequestBody AccommodationBookingInputDto booking) {
        return toDto(bookingLogic.changeAccommodation(user.appUserId(), bookingId, toInput(booking)));
    }

    @Operation(summary = "Delete a booking")
    @DeleteMapping("/bookings/{bookingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBooking(@AuthenticationPrincipal CurrentUser user, @PathVariable long bookingId) {
        bookingLogic.delete(user.appUserId(), bookingId);
    }

    @Operation(summary = "Assistant status", description = "Whether confirmations can be read automatically.")
    @GetMapping("/bookings/assistant")
    public AssistantStatusDto assistantStatus() {
        return new AssistantStatusDto(extractor.isAvailable());
    }

    @Operation(summary = "Read an accommodation booking from a confirmation",
            description = "Pasted or shared text and/or a screenshot (JPEG, PNG, WebP, GIF) or PDF up to 5 MB. The "
                    + "content is sent to the language model (OpenAI) and not stored. 503 ASSISTANT_UNAVAILABLE if "
                    + "the assistant is not configured or fails, 429 ASSISTANT_RATE_LIMITED.")
    @PostMapping(value = "/bookings/accommodation-drafts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AccommodationDraftDto extractAccommodation(@AuthenticationPrincipal CurrentUser user,
                                                      @RequestPart(value = "text", required = false) @Nullable String text,
                                                      @RequestPart(value = "file", required = false) @Nullable MultipartFile file)
            throws IOException {
        AccommodationDraft draft = extractor.extract(user.appUserId(), text,
                file == null ? null : file.getOriginalFilename(), file == null ? null : file.getContentType(),
                file == null ? null : file.getBytes());
        return new AccommodationDraftDto(draft.found(), draft.name(), draft.addressLine(), draft.countryCode(),
                draft.phone(), draft.email(), draft.website(), draft.provider(), draft.reference(), draft.manageUrl(),
                draft.checkInDate(), draft.checkInTime(), draft.checkOutDate(), draft.checkOutTime(),
                draft.checkInFrom(), draft.checkInUntil(), draft.checkOutUntil(), draft.guests(), draft.rooms(),
                draft.price(), draft.currency(), draft.cancellationDeadline(), draft.uncertainFields());
    }

    private static NewAccommodationBooking toInput(AccommodationBookingInputDto dto) {
        return new NewAccommodationBooking(dto.source() == null ? BookingSource.MANUAL : dto.source(), dto.name(),
                dto.addressLine(), dto.countryCode(), dto.latitude(), dto.longitude(), dto.placeRef(), dto.phone(),
                dto.email(), dto.website(), dto.provider(), dto.reference(), dto.manageUrl(), dto.checkIn(),
                dto.checkOut(), dto.fallbackTimeZone(), dto.checkInFrom(), dto.checkInUntil(), dto.checkOutUntil(),
                dto.guests(), dto.rooms(), dto.price(), dto.currency(), dto.cancellationDeadline(), dto.notes());
    }

    private static BookingDto toDto(BookingView view) {
        Booking booking = view.booking();
        AccommodationDetails details = view.accommodation();
        AccommodationDto accommodation = details == null ? null : new AccommodationDto(details.getName(),
                details.getAddressLine(), details.getCountryCode(), details.getLatitude(), details.getLongitude(),
                details.getTimeZone(), details.getPhone(), details.getEmail(), details.getWebsite(),
                details.getPlaceRef(), details.getCheckInFrom(), details.getCheckInUntil(), details.getCheckOutUntil(),
                details.getGuests(), details.getRooms());
        return new BookingDto(booking.getId(), booking.getStopId(), booking.getType(), booking.getStatus(),
                booking.getSource(), booking.getTitle(), booking.getProvider(), booking.getReference(),
                booking.getManageUrl(), booking.getStartsAt(), booking.getEndsAt(), booking.getPrice(),
                booking.getCurrency(), booking.getCancellationDeadline(), booking.getNotes(), accommodation);
    }
}
