package com.nfv.accounting.api;

import com.nfv.accounting.dto.TopUpDto;
import com.nfv.accounting.dto.TopUpRequestDto;
import com.nfv.accounting.dto.TopUpStartedDto;
import com.nfv.accounting.entities.TopUp;
import com.nfv.accounting.logic.TopUpLogic;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Currency;
import java.util.List;

/**
 * Consumers top up platform credit via PayPal: start, approve at PayPal, capture, see Docs/accounting.md.
 */
@Tag(name = "Top-ups", description = "Top up platform credit via PayPal")
@RestController
@RequestMapping("/api/v1/accounting/top-ups")
@RequiredArgsConstructor
public class TopUpController {
    private static final int LIST_LIMIT = 20;

    private final TopUpLogic topUpLogic;
    private final AccountingDtoMapper mapper;

    @Operation(summary = "Start a top-up",
            description = "Creates a PayPal order over the amount. Send the user to approveUrl; PayPal returns to the "
                    + "configured return URL with the order id as query parameter token, then call capture.")
    @ApiResponse(responseCode = "201", description = "PayPal order created")
    @ApiResponse(responseCode = "400", description = "Invalid amount or unsupported currency (INVALID_AMOUNT)", content = @Content)
    @ApiResponse(responseCode = "422", description = "Amount below the minimum or above the maximum (AMOUNT_OUT_OF_RANGE)", content = @Content)
    @ApiResponse(responseCode = "503", description = "PayPal not configured or no current exchange rate", content = @Content)
    @PostMapping
    public ResponseEntity<TopUpStartedDto> startTopUp(@AuthenticationPrincipal CurrentUser user,
                                                      @RequestBody TopUpRequestDto request) {
        AccountingDtoMapper.Booking booking = mapper.toBooking(
                request.amount() == null ? null : request.amount().amount(),
                request.amount() == null ? null : request.amount().currency());
        TopUpLogic.StartedTopUp started = topUpLogic.start(user.appUserId(), booking.base(), booking.original());
        Currency currency = Currency.getInstance(booking.original().currency());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TopUpStartedDto(mapper.toDto(started.topUp(), currency), started.approveUrl()));
    }

    @Operation(summary = "Capture an approved top-up",
            description = "Idempotent. Status COMPLETED means credited; PENDING is credited automatically once PayPal "
                    + "releases the money.")
    @ApiResponse(responseCode = "200", description = "Current state of the top-up")
    @ApiResponse(responseCode = "404", description = "No top-up of the user for this order (TOP_UP_NOT_FOUND)", content = @Content)
    @ApiResponse(responseCode = "422", description = "Not approved or declined (PAYMENT_NOT_APPROVED, PAYMENT_DECLINED)", content = @Content)
    @PostMapping("/paypal/{orderId}/capture")
    public TopUpDto captureTopUp(@AuthenticationPrincipal CurrentUser user,
                                 @Parameter(description = "PayPal order id, query parameter token of the return URL")
                                 @PathVariable String orderId) {
        return toDto(user, topUpLogic.capture(user.appUserId(), orderId));
    }

    @Operation(summary = "Cancel a top-up", description = "After the user cancelled at PayPal. Paid top-ups stay as they are.")
    @ApiResponse(responseCode = "404", description = "No top-up of the user for this order (TOP_UP_NOT_FOUND)", content = @Content)
    @PostMapping("/paypal/{orderId}/cancel")
    public TopUpDto cancelTopUp(@AuthenticationPrincipal CurrentUser user,
                                @Parameter(description = "PayPal order id, query parameter token of the cancel URL")
                                @PathVariable String orderId) {
        return toDto(user, topUpLogic.cancel(user.appUserId(), orderId));
    }

    @Operation(summary = "Latest top-ups", description = "The 20 latest top-ups, newest first.")
    @GetMapping
    public List<TopUpDto> getTopUps(@AuthenticationPrincipal CurrentUser user) {
        Currency currency = mapper.responseCurrency(user, null);
        return topUpLogic.latest(user.appUserId(), LIST_LIMIT).stream().map(topUp -> mapper.toDto(topUp, currency)).toList();
    }

    private TopUpDto toDto(CurrentUser user, TopUp topUp) {
        return mapper.toDto(topUp, mapper.responseCurrency(user, null));
    }
}
