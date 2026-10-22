package com.vacantagent.user.dto;

import jakarta.annotation.Nullable;

/** Balances are provided by {@code GET /api/v1/accounting/balances}. */
public record AppUserDto(Long id, String username, @Nullable String preferredCurrency) {
}
