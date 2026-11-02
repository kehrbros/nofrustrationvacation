package com.nfv.accounting.api;

import com.nfv.accounting.dto.PayoutDto;
import com.nfv.accounting.dto.PayoutRequestDto;
import com.nfv.accounting.logic.InsufficientBalanceException;
import com.nfv.accounting.logic.PayoutLogic;
import com.nfv.common.money.Money;
import com.nfv.currency.dto.MoneyDto;
import com.nfv.currency.logic.MoneyRounding;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Providers pay out earnings to their linked PayPal account, see Docs/accounting.md.
 */
@Tag(name = "Payouts", description = "Pay out earnings via PayPal")
@RestController
@RequestMapping("/api/v1/accounting/payouts")
@RequiredArgsConstructor
public class PayoutController {
    private static final int LIST_LIMIT = 20;

    private final PayoutLogic payoutLogic;
    private final AccountingDtoMapper mapper;

    @Operation(summary = "Request a payout",
            description = "Debits the earnings and sends the amount to the linked PayPal account. PayPal processes payouts "
                    + "asynchronously: SUBMITTED becomes COMPLETED or FAILED (booked back) later.")
    @ApiResponse(responseCode = "201", description = "Payout created, see status")
    @ApiResponse(responseCode = "400", description = "Invalid amount or unsupported currency (INVALID_AMOUNT)", content = @Content)
    @ApiResponse(responseCode = "409", description = "No PayPal account linked (PAYOUT_ACCOUNT_REQUIRED) or earnings too low "
            + "(INSUFFICIENT_BALANCE)", content = @Content)
    @ApiResponse(responseCode = "422", description = "Amount below the minimum (AMOUNT_OUT_OF_RANGE)", content = @Content)
    @PostMapping
    public ResponseEntity<PayoutDto> requestPayout(@AuthenticationPrincipal CurrentUser user,
                                                   @RequestBody PayoutRequestDto request) {
        BigDecimal amount = request.amount();
        if (amount == null) {
            // everything: the earnings converted and rounded down, so the base amount never exceeds the balance
            Money available = payoutLogic.available(user.appUserId());
            if (available.isZero()) {
                throw new InsufficientBalanceException("No earnings to pay out");
            }
            MoneyDto all = mapper.toDto(available, mapper.responseCurrency(user, request.currency()), MoneyRounding.PAYOUT);
            amount = all.amount();
        }
        AccountingDtoMapper.Booking booking = mapper.toBooking(amount, request.currency());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toDto(payoutLogic.request(user.appUserId(), booking.base(), booking.original())));
    }

    @Operation(summary = "Latest payouts", description = "The 20 latest payouts, newest first.")
    @GetMapping
    public List<PayoutDto> getPayouts(@AuthenticationPrincipal CurrentUser user) {
        return payoutLogic.latest(user.appUserId(), LIST_LIMIT).stream().map(mapper::toDto).toList();
    }
}
