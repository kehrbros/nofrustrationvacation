package com.nfv.advisor.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nfv.advisor.entities.PlanExecutionStatus;
import com.nfv.bookings.entities.PlanPaymentStatus;
import com.nfv.bookings.entities.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Taking a plan with hotels: approve orderId with the PayPal button (or open approveUrl), then "
        + "POST /plan-executions/{executionId}/completion. AWAITING_PAYMENT → APPLIED (trip changed, hotels paid), "
        + "FAILED (nothing paid, trip unchanged) or APPLIED_PARTLY (hotels paid, trip not changed, see failure).")
public record PlanExecutionDto(
        long executionId,
        long tripId,
        PlanExecutionStatus status,
        @Nullable String failure,
        @Nullable PlanPaymentStatus paymentStatus,
        @Schema(description = "PayPal order for the button") @Nullable String orderId,
        @Schema(description = "Only right after the checkout, for the redirect fallback") @Nullable String approveUrl,
        @Schema(type = "string", format = "decimal", example = "128.00")
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable BigDecimal total,
        @Nullable String currency,
        List<PlanHotel> hotels,
        List<Long> plannedRouteIds,
        int todosAdded) {

    public record PlanHotel(String hotelName,
                            @Schema(type = "string", format = "decimal", example = "128.00")
                            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price,
                            String currency, ReservationStatus status, @Nullable Long bookingId) {
    }
}
