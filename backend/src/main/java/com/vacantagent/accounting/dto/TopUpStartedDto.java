package com.vacantagent.accounting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** @param approveUrl PayPal page where the consumer approves the payment */
public record TopUpStartedDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TopUpDto topUp,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String approveUrl) {
}
