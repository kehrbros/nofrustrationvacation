package com.vacantagent.user.dto;

import com.vacantagent.currency.dto.MoneyDto;
import jakarta.annotation.Nullable;

public record AppUserDto(Long id, String username, @Nullable String preferredCurrency, MoneyDto accountBalance) {
}
