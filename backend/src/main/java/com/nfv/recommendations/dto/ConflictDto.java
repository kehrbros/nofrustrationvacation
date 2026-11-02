package com.nfv.recommendations.dto;

import com.nfv.incidents.entities.Impact;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

@Schema(description = "Incident passed during its validity")
public record ConflictDto(
        long incidentId,
        Impact impact,
        @Schema(description = "Expected delay in seconds, null if unknown") @Nullable Long delaySeconds) {
}