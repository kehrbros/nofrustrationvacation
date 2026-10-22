package com.vacantagent.accounting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Query parameters {@code code} and {@code state} PayPal appended to the redirect URI. */
public record PayPalLinkDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String state) {
}
