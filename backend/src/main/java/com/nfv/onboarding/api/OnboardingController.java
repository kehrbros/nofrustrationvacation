package com.nfv.onboarding.api;

import com.nfv.onboarding.dto.ProviderOnboardingDto;
import com.nfv.onboarding.logic.ProviderOnboarding;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding", description = "Steps before a user can act in a role")
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {
    private final ProviderOnboarding providerOnboarding;

    @Operation(summary = "Provider onboarding checklist",
            description = "Registration and PayPal account for payouts.")
    @GetMapping("/provider")
    public ProviderOnboardingDto getProviderOnboarding(@AuthenticationPrincipal CurrentUser user) {
        ProviderOnboarding.Status status = providerOnboarding.status(user.appUserId());
        return new ProviderOnboardingDto(status.registered(), status.payoutAccountLinked(), status.complete());
    }
}
