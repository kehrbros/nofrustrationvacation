package com.nfv.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Name, period and travelers of a trip. Points in time with the offset of the user's time zone")
public record UpdateTripDto(String name, OffsetDateTime startAt, OffsetDateTime endAt,
                            @Schema(description = "Number of travelers (1-99), null removes it")
                            @Nullable Integer travelers) {
}
