package com.nfv.accounting.api;

import com.nfv.accounting.dto.PayPalAuthorizationDto;
import com.nfv.accounting.dto.PayPalLinkDto;
import com.nfv.accounting.dto.PayoutAccountStatusDto;
import com.nfv.accounting.logic.PayoutAccountLogic;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Linking the PayPal account providers get paid out to ("Log in with PayPal"). Required for the provider onboarding.
 */
@Tag(name = "Payout account", description = "Link the PayPal account for payouts")
@RestController
@RequestMapping("/api/v1/accounting/payout-account")
@RequiredArgsConstructor
public class PayoutAccountController {
    private final PayoutAccountLogic payoutAccountLogic;
    private final AccountingDtoMapper mapper;

    @Operation(summary = "Linked payout account", description = "Also tells whether PayPal is available on the server.")
    @GetMapping
    public PayoutAccountStatusDto getPayoutAccount(@AuthenticationPrincipal CurrentUser user) {
        return mapper.toStatus(payoutAccountLogic.find(user.appUserId()).orElse(null));
    }

    @Operation(summary = "Start linking a PayPal account",
            description = "Returns the PayPal login URL. PayPal sends the user back to the configured redirect URI with "
                    + "code and state, which the frontend passes to POST /payout-account/paypal within 15 minutes.")
    @ApiResponse(responseCode = "200", description = "Send the user to authorizationUrl")
    @ApiResponse(responseCode = "503", description = "PayPal is not configured", content = @Content)
    @PostMapping("/paypal/authorization")
    public PayPalAuthorizationDto startPayPalLink(@AuthenticationPrincipal CurrentUser user) {
        return new PayPalAuthorizationDto(payoutAccountLogic.startLink(user.appUserId()).toString());
    }

    @Operation(summary = "Complete linking a PayPal account", description = "Replaces a PayPal account linked before.")
    @ApiResponse(responseCode = "200", description = "Linked")
    @ApiResponse(responseCode = "400", description = "State unknown, expired or of another user (LINK_STATE_INVALID)", content = @Content)
    @ApiResponse(responseCode = "409", description = "PayPal account linked to another user (PAYPAL_ACCOUNT_IN_USE)", content = @Content)
    @ApiResponse(responseCode = "502", description = "PayPal refused the code or could not be reached", content = @Content)
    @PostMapping("/paypal")
    public PayoutAccountStatusDto completePayPalLink(@AuthenticationPrincipal CurrentUser user,
                                                     @RequestBody PayPalLinkDto link) {
        return mapper.toStatus(payoutAccountLogic.completeLink(user.appUserId(), link.code(), link.state()));
    }

    @Operation(summary = "Unlink the payout account", description = "Reopens the step in the provider onboarding.")
    @ApiResponse(responseCode = "204", description = "Unlinked or nothing was linked")
    @ApiResponse(responseCode = "409", description = "Payouts are still in progress (PAYOUTS_OPEN)", content = @Content)
    @DeleteMapping
    public ResponseEntity<Void> unlinkPayoutAccount(@AuthenticationPrincipal CurrentUser user) {
        payoutAccountLogic.unlink(user.appUserId());
        return ResponseEntity.noContent().build();
    }
}
