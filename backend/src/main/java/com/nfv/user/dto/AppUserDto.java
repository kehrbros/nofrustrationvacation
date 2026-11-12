package com.nfv.user.dto;

import jakarta.annotation.Nullable;

public record AppUserDto(Long id, String username, @Nullable String preferredCurrency) {
}
