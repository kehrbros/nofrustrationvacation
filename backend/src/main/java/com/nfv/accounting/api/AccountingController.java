package com.nfv.accounting.api;

import com.nfv.accounting.dto.BalancesDto;
import com.nfv.accounting.dto.LedgerEntryDto;
import com.nfv.accounting.entities.AccountType;
import com.nfv.accounting.logic.AccountLedger;
import com.nfv.currency.logic.MoneyRounding;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Currency;
import java.util.List;

@Tag(name = "Accounting", description = "Balances and bookings of the authenticated user")
@RestController
@RequestMapping("/api/v1/accounting")
@RequiredArgsConstructor
public class AccountingController {
    private static final int ENTRY_LIMIT = 50;

    private final AccountLedger ledger;
    private final AccountingDtoMapper mapper;

    @Operation(summary = "Credit and earnings",
            description = "Credit rounded commercially, earnings rounded down (what can be paid out).")
    @GetMapping("/balances")
    public BalancesDto getBalances(@AuthenticationPrincipal CurrentUser user,
                                   @Parameter(description = "ISO 4217 code, defaults to the preferred currency")
                                   @RequestParam(required = false) @Nullable String currency) {
        Currency target = mapper.responseCurrency(user, currency);
        return new BalancesDto(
                mapper.toDto(ledger.balance(user.appUserId(), AccountType.CREDIT), target, MoneyRounding.AMOUNT),
                mapper.toDto(ledger.balance(user.appUserId(), AccountType.EARNINGS), target, MoneyRounding.PAYOUT));
    }

    @Operation(summary = "Latest bookings", description = "The 50 latest bookings on all accounts of the user, newest first.")
    @GetMapping("/entries")
    public List<LedgerEntryDto> getEntries(@AuthenticationPrincipal CurrentUser user,
                                           @Parameter(description = "ISO 4217 code, defaults to the preferred currency")
                                           @RequestParam(required = false) @Nullable String currency) {
        Currency target = mapper.responseCurrency(user, currency);
        return ledger.latestEntries(user.appUserId(), ENTRY_LIMIT).stream().map(entry -> mapper.toDto(entry, target)).toList();
    }
}
