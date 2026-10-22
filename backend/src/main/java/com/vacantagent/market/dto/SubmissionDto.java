package com.vacantagent.market.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

/** Outcome of placing an order or offer. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmissionDto(
        @Schema(description = "Id of the persisted order or offer, use it to cancel")
        long id,
        SubmissionStatus status,
        @Nullable @Schema(description = "Only for REJECTED")
        String reason,
        @Nullable @Schema(description = "Only for MATCHED")
        AgreementDto agreement) {

    public enum SubmissionStatus {
        /** Paired immediately, see {@code agreement}. */
        MATCHED,
        /** Waiting in the market for a counterpart. */
        RESTING,
        /** Refused by the market rules, see {@code reason}. */
        REJECTED
    }
}