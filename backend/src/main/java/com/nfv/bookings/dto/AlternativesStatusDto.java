package com.nfv.bookings.dto;

import jakarta.annotation.Nullable;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether alternatives can be searched (LiteAPI), booked (LiteAPI and PayPal) and ranked "
        + "(assistant)")
public record AlternativesStatusDto(boolean searchAvailable, boolean bookingAvailable, boolean rankingAvailable,
                                    @Schema(description = "Public client id for the PayPal JS SDK, null without PayPal")
                                    @Nullable String payPalClientId,
                                    @Schema(description = "sandbox or live") String payPalEnvironment) {
}
