package com.vacantagent.trips.api;

import com.vacantagent.trips.dto.CreateStopDto;
import com.vacantagent.trips.dto.CreateTripDto;
import com.vacantagent.trips.dto.RouteDto;
import com.vacantagent.trips.dto.StopDto;
import com.vacantagent.trips.dto.TripDetailDto;
import com.vacantagent.trips.dto.TripSummaryDto;
import com.vacantagent.trips.entities.Route;
import com.vacantagent.trips.entities.Stop;
import com.vacantagent.trips.entities.Trip;
import com.vacantagent.trips.logic.InvalidTripInputException;
import com.vacantagent.trips.logic.NewStop;
import com.vacantagent.trips.logic.TripLogic;
import com.vacantagent.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Tag(name = "Trips", description = "Trips, stops and title photos of the authenticated user")
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripController {
    private final TripLogic tripLogic;
    private final Clock clock;

    @Operation(summary = "All trips", description = "Ascending by start, with the phase relative to now.")
    @GetMapping
    public List<TripSummaryDto> listTrips(@AuthenticationPrincipal CurrentUser user) {
        return tripLogic.list(user.appUserId()).stream()
                .map(overview -> new TripSummaryDto(overview.trip().getId(), overview.trip().getName(),
                        overview.trip().getStartAt(), overview.trip().getEndAt(), overview.phase(), overview.stopCount(),
                        overview.nextStop() == null ? null : overview.nextStop().getName(), overview.hasCoverImage()))
                .toList();
    }

    @Operation(summary = "Create a trip")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TripDetailDto createTrip(@AuthenticationPrincipal CurrentUser user, @RequestBody CreateTripDto trip) {
        Trip created = tripLogic.create(user.appUserId(), trip.name(), trip.startAt(), trip.endAt());
        return toDetail(created, false, List.of(), List.of());
    }

    @Operation(summary = "Trip with stops and routes")
    @GetMapping("/{tripId}")
    public TripDetailDto getTrip(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId) {
        Trip trip = tripLogic.get(user.appUserId(), tripId);
        return toDetail(trip, tripLogic.coverImage(user.appUserId(), tripId).isPresent(),
                tripLogic.stops(user.appUserId(), tripId), tripLogic.routes(user.appUserId(), tripId));
    }

    @Operation(summary = "Append a stop", description = "From the second stop on, a route from the previous stop is created.")
    @PostMapping("/{tripId}/stops")
    @ResponseStatus(HttpStatus.CREATED)
    public StopDto addStop(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                           @RequestBody CreateStopDto stop) {
        return toDto(tripLogic.addStop(user.appUserId(), tripId,
                new NewStop(stop.name(), stop.latitude(), stop.longitude(), stop.arrivalAt(), stop.departureAt())));
    }

    @Operation(summary = "Title photo", description = "Image bytes, 404 if the trip has none.")
    @GetMapping(value = "/{tripId}/cover-image", produces = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp"})
    public ResponseEntity<byte[]> getCoverImage(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId) {
        return tripLogic.coverImage(user.appUserId(), tripId)
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.getContentType()))
                        .cacheControl(CacheControl.noCache().cachePrivate())
                        .body(image.getContent()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Upload a title photo", description = "JPEG, PNG or WebP up to 5 MB, replaces the current one.")
    @PutMapping(value = "/{tripId}/cover-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadCoverImage(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                                 @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > TripLogic.MAX_COVER_IMAGE_BYTES) {
            throw new InvalidTripInputException("title photo must have 1 byte to 5 MB");
        }
        tripLogic.uploadCoverImage(user.appUserId(), tripId,
                String.valueOf(file.getContentType()), file.getBytes());
    }

    private TripDetailDto toDetail(Trip trip, boolean hasCoverImage, List<Stop> stops, List<Route> routes) {
        return new TripDetailDto(trip.getId(), trip.getName(), trip.getStartAt(), trip.getEndAt(),
                trip.phaseAt(OffsetDateTime.now(clock)), hasCoverImage, stops.stream().map(TripController::toDto).toList(),
                routes.stream().map(route -> new RouteDto(route.getId(), route.getFromStop().getId(),
                        route.getToStop().getId(), route.getPlannedAt())).toList());
    }

    private static StopDto toDto(Stop stop) {
        return new StopDto(stop.getId(), stop.getPosition(), stop.getName(), stop.getLatitude(), stop.getLongitude(),
                stop.getArrivalAt(), stop.getDepartureAt());
    }
}
