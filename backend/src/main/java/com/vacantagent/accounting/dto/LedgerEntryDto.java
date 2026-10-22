package com.vacantagent.accounting.dto;

import com.vacantagent.accounting.entities.AccountType;
import com.vacantagent.accounting.entities.EntryType;
import com.vacantagent.currency.dto.MoneyDto;
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
