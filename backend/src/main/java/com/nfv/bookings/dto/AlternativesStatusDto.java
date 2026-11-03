package com.nfv.bookings.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether alternatives can be searched (LiteAPI), booked (LiteAPI and PayPal) and ranked "
        + "(assistant)")
public record AlternativesStatusDto(boolean searchAvailable, boolean bookingAvailable, boolean rankingAvailable) {
}
