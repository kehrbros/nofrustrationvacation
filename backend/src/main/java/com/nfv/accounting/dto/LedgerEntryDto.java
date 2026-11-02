package com.nfv.accounting.dto;

import com.nfv.accounting.entities.AccountType;
import com.nfv.accounting.entities.EntryType;
import com.nfv.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/** One booking, the amount is negative for debits and converted at the rate of its booking date. */
public record LedgerEntryDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccountType account,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EntryType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto amount,
        @Nullable Long referenceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime bookedAt) {
}
