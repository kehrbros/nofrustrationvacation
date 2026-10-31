package com.vacantagent.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "New trip. Points in time with the offset of the user's time zone, e.g. 2027-07-12T00:00:00+02:00")
public record CreateTripDto(String name, OffsetDateTime startAt, OffsetDateTime endAt) {
}
