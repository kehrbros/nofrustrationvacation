package com.nfv.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Name and period of a trip. Points in time with the offset of the user's time zone")
public record UpdateTripDto(String name, OffsetDateTime startAt, OffsetDateTime endAt) {
}