package com.vacantagent.trips.api;

import com.vacantagent.trips.dto.CreateStopDto;
import com.vacantagent.trips.dto.CreateTripDto;
import com.vacantagent.trips.dto.MoveStopDto;
import com.vacantagent.trips.dto.TripDetailDto;
import com.vacantagent.trips.dto.TripSummaryDto;
import com.vacantagent.trips.dto.UpdateStopDto;
import com.vacantagent.trips.dto.UpdateTripDto;
import com.vacantagent.trips.entities.Trip;
import com.vacantagent.trips.logic.InvalidTripInputException;
import com.vacantagent.trips.logic.NewStop;
import com.vacantagent.trips.logic.TripEditing;
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

@Tag(name = "Trips", description = "Trips, stops and title photos of the authenticated user")
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripController {
    private final TripLogic tripLogic;
    private final TripEditing tripEditing;
    private final TripDtos tripDtos;

    @Operation(summary = "All trips", description = "Ascending by start, with the phase relative to now.")
    @GetMapping
    public List<TripSummaryDto> listTrips(@AuthenticationPrincipal CurrentUser user) {
        return tripDtos.list(user.appUserId());
    }

    @Operation(summary = "Create a trip")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TripDetailDto createTrip(@AuthenticationPrincipal CurrentUser user, @RequestBody CreateTripDto trip) {
        Trip created = tripLogic.create(user.appUserId(), trip.name(), trip.startAt(), trip.endAt());
        return tripDtos.detail(user.appUserId(), created.getId());
    }

    @Operation(summary = "Trip with stops and routes")
    @GetMapping("/{tripId}")
    public TripDetailDto getTrip(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId) {
        return tripDtos.detail(user.appUserId(), tripId);
    }

    @Operation(summary = "Change name and period of a trip", description = "Routes are not planned again.")
    @PutMapping("/{tripId}")
    public TripDetailDto updateTrip(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                                    @RequestBody UpdateTripDto trip) {
        tripLogic.update(user.appUserId(), tripId, trip.name(), trip.startAt(), trip.endAt());
        return tripDtos.detail(user.appUserId(), tripId);
    }

    @Operation(summary = "Delete a trip", description = "With its stops, routes, waypoints, title photo and incidents.")
    @DeleteMapping("/{tripId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTrip(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId) {
        tripLogic.delete(user.appUserId(), tripId);
    }

    @Operation(summary = "Add a stop", description = "Inserted at the position or appended. Routes to its neighbours "
            + "are created and planned, see planOutcome of the routes.")
    @PostMapping("/{tripId}/stops")
    @ResponseStatus(HttpStatus.CREATED)
    public TripDetailDto addStop(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                                 @RequestBody CreateStopDto stop) {
        tripEditing.addStop(user.appUserId(), tripId,
                new NewStop(stop.name(), stop.latitude(), stop.longitude(), stop.arrivalAt(), stop.departureAt()),
                stop.position());
        return tripDtos.detail(user.appUserId(), tripId);
    }

    @Operation(summary = "Change a stop", description = "New coordinates or departure plan the affected routes again.")
    @PutMapping("/{tripId}/stops/{stopId}")
    public TripDetailDto updateStop(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                                    @PathVariable long stopId, @RequestBody UpdateStopDto stop) {
        tripEditing.updateStop(user.appUserId(), tripId, stopId,
                new NewStop(stop.name(), stop.latitude(), stop.longitude(), stop.arrivalAt(), stop.departureAt()));
        return tripDtos.detail(user.appUserId(), tripId);
    }

    @Operation(summary = "Move a stop", description = "Routes between new neighbours are created and planned.")
    @PutMapping("/{tripId}/stops/{stopId}/position")
    public TripDetailDto moveStop(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                                  @PathVariable long stopId, @RequestBody MoveStopDto move) {
        tripEditing.moveStop(user.appUserId(), tripId, stopId, move.position());
        return tripDtos.detail(user.appUserId(), tripId);
    }

    @Operation(summary = "Delete a stop", description = "Its neighbours get a new route, which is planned.")
    @DeleteMapping("/{tripId}/stops/{stopId}")
    public TripDetailDto removeStop(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                                    @PathVariable long stopId) {
        tripEditing.removeStop(user.appUserId(), tripId, stopId);
        return tripDtos.detail(user.appUserId(), tripId);
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
}