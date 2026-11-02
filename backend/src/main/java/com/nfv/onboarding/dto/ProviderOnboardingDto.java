package com.nfv.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Checklist of the provider onboarding, the order of the steps does not matter. */
public record ProviderOnboardingDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean registered,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean payoutAccountLinked,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "All steps done")
        boolean complete) {
}
