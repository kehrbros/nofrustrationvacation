package com.nfv.bookings.api;

import com.nfv.bookings.dto.AccommodationBookingInputDto;
import com.nfv.bookings.dto.AccommodationDraftDto;
import com.nfv.bookings.dto.AccommodationDto;
import com.nfv.bookings.dto.AccommodationFactsDto;
import com.nfv.bookings.dto.AccommodationQueryDto;
import com.nfv.bookings.dto.AccommodationSuggestionDto;
import com.nfv.bookings.dto.AssistantStatusDto;
import com.nfv.bookings.dto.BookingDto;
import com.nfv.bookings.dto.BookingTodoDto;
import com.nfv.bookings.entities.AccommodationDetails;
import com.nfv.bookings.entities.Booking;
import com.nfv.bookings.entities.BookingSource;
import com.nfv.bookings.entities.BookingTodo;
import com.nfv.bookings.logic.AccommodationDraft;
import com.nfv.bookings.logic.AccommodationExtractor;
import com.nfv.bookings.logic.AccommodationFacts;
import com.nfv.bookings.logic.AccommodationLookup;
import com.nfv.bookings.logic.AccommodationQuery;
import com.nfv.bookings.logic.BookingLogic;
import com.nfv.bookings.logic.BookingTodos;
import com.nfv.bookings.logic.BookingView;
import com.nfv.bookings.logic.NewAccommodationBooking;
import com.nfv.bookings.logic.StopConflict;
import com.nfv.trips.logic.TripEditing;
import com.nfv.user.security.CurrentUser;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final AccommodationLookup lookup;
    private final BookingTodos todos;
    private final TripEditing tripEditing;

    @Operation(summary = "To-do list of a trip", description = "Bookings to cancel or to tell about a later arrival "
            + "after the trip changed, the newest first, also those done.")
    @GetMapping("/trips/{tripId}/booking-todos")
    public List<BookingTodoDto> listTodos(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId) {
        return todos.list(user.appUserId(), tripId).stream().map(BookingController::toDto).toList();
    }

    @Operation(summary = "Mark a to-do as done", description = "CANCEL also marks the booking as cancelled in the "
            + "app, the provider is not contacted.")
    @PostMapping("/booking-todos/{todoId}/completion")
    public BookingTodoDto completeTodo(@AuthenticationPrincipal CurrentUser user, @PathVariable long todoId) {
        return toDto(todos.done(user.appUserId(), todoId));
    }

    @Operation(summary = "Open a to-do again", description = "The booking stays as it is.")
    @DeleteMapping("/booking-todos/{todoId}/completion")
    public BookingTodoDto reopenTodo(@AuthenticationPrincipal CurrentUser user, @PathVariable long todoId) {
        return toDto(todos.reopen(user.appUserId(), todoId));
    }

    static BookingTodoDto toDto(BookingTodo todo) {
        return new BookingTodoDto(todo.getId(), todo.getTripId(), todo.getBookingId(), todo.getKind(),
                todo.getReason(), todo.getTitle(), todo.getProvider(), todo.getReference(), todo.getManageUrl(),
                todo.getPhone(), todo.getEmail(), todo.getStartsAt(), todo.getEndsAt(), todo.getCancellationDeadline(),
                todo.isBookedInApp(), todo.isCancellableInApp(), todo.getNewArrivalOn(), todo.getCreatedAt(), todo.getDoneAt());
    }


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

    @Operation(summary = "Replace an accommodation booking", description = "Source and status stay. Check-in and "
            + "check-out must lie within the days of the stop, otherwise 409 BOOKING_OUTSIDE_STOP with the stop's "
            + "arrival/departure, both extended to the booking and whether the booking can be shortened. "
            + "stopConflict=EXTEND_STOP moves the stop to the days of the booking, TRIM_BOOKING shortens the booking.")
    @PutMapping("/accommodation-bookings/{bookingId}")
    public BookingDto changeAccommodationBooking(@AuthenticationPrincipal CurrentUser user,
                                                 @PathVariable long bookingId,
                                                 @RequestBody AccommodationBookingInputDto booking,
                                                 @RequestParam(required = false) @Nullable StopConflict stopConflict) {
        BookingLogic.ChangedBooking changed = bookingLogic.changeAccommodation(user.appUserId(), bookingId,
                toInput(booking), stopConflict);
        // a stop with a new departure gets its route planned again, outside the transaction
        tripEditing.plan(changed.change());
        return toDto(changed.view());
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

    @Operation(summary = "Suggest accommodations by name",
            description = "LiteAPI hotels whose name contains the query (at least 3 characters). With latitude and "
                    + "longitude (the stop) those within 30 km come first, nearest first. Empty if LiteAPI is not "
                    + "configured or not reachable.")
    @GetMapping("/bookings/accommodation-suggestions")
    public List<AccommodationSuggestionDto> suggestAccommodations(@RequestParam String query,
                                                                  @RequestParam(required = false) @Nullable Double latitude,
                                                                  @RequestParam(required = false) @Nullable Double longitude) {
        return lookup.suggest(query, latitude, longitude).stream()
                .map(s -> new AccommodationSuggestionDto(s.hotelId(), s.name(), s.addressLine(), s.countryCode(),
                        s.latitude(), s.longitude(), s.stars()))
                .toList();
    }

    @Operation(summary = "Look up contact and house rules of an accommodation",
            description = "From the LiteAPI hotel data (with hotelId); missing fields are looked up on the web by the "
                    + "assistant (OpenAI web search, only name, address and coordinates of the accommodation are "
                    + "sent). Counts towards the assistant's rate limit when it is asked.")
    @PostMapping("/bookings/accommodation-facts")
    public AccommodationFactsDto lookUpAccommodation(@AuthenticationPrincipal CurrentUser user,
                                                     @RequestBody AccommodationQueryDto query) {
        AccommodationFacts facts = lookup.facts(user.appUserId(), new AccommodationQuery(query.hotelId(), query.name(),
                query.addressLine(), query.countryCode(), query.latitude(), query.longitude()));
        return new AccommodationFactsDto(facts.addressLine(), facts.countryCode(), facts.phone(), facts.email(),
                facts.website(), facts.checkInFrom(), facts.checkInUntil(), facts.checkOutUntil(), facts.fromLiteApi(),
                facts.fromAssistant(), facts.researched(), facts.sources());
    }

    private static NewAccommodationBooking toInput(AccommodationBookingInputDto dto) {
        return new NewAccommodationBooking(dto.source() == null ? BookingSource.MANUAL : dto.source(), dto.name(),
                dto.addressLine(), dto.countryCode(), dto.latitude(), dto.longitude(), dto.placeRef(), dto.phone(),
                dto.email(), dto.website(), dto.provider(), dto.reference(), dto.manageUrl(), dto.checkIn(),
                dto.checkOut(), dto.fallbackTimeZone(), dto.checkInFrom(), dto.checkInUntil(), dto.checkOutUntil(),
                dto.guests(), dto.rooms(), dto.price(), dto.currency(), dto.cancellationDeadline(), dto.notes());
    }

    static BookingDto toDto(BookingView view) {
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
