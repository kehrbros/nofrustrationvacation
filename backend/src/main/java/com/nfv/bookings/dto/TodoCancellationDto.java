package com.nfv.bookings.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;

@Schema(description = "A to-do cancelled in the app: the booking is cancelled at LiteAPI, PayPal refunds refundAmount.")
public record TodoCancellationDto(
        BookingTodoDto todo,
        @Schema(type = "string", format = "decimal", example = "189.00", description = "Refunded with PayPal")
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable BigDecimal refundAmount,
        String currency,
        @Schema(description = "The refund with PayPal is made (otherwise it follows in the background)")
        boolean refunded) {
}
