package com.nfv.accounting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/**
 * @param linked          a payout account is linked, required for the provider onboarding
 * @param payPalAvailable PayPal is configured on the server, linking and payments are possible
 * @param email           e-mail address of the linked PayPal account
 * @param verified        PayPal verified the linked account
 */
public record PayoutAccountStatusDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean linked,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean payPalAvailable,
        @Nullable String provider,
        @Nullable String email,
        @Nullable Boolean verified,
        @Nullable OffsetDateTime linkedAt) {
}
