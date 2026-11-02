package com.nfv.user.logic;

import com.nfv.user.data.AppUserRepository;
import com.nfv.user.entities.AppUser;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppUserSettings {
    private final AppUserRepository appUserRepository;

    @Transactional(readOnly = true)
    public AppUser get(long appUserId) {
        return appUserRepository.findById(appUserId).orElseThrow();
    }

    /**
     * @param currencyCode already validated against the supported currencies, {@code null} for the base currency
     */
    @Transactional
    public AppUser changePreferredCurrency(long appUserId, @Nullable String currencyCode) {
        AppUser user = get(appUserId);
        user.changePreferredCurrency(currencyCode);
        return user;
    }
}
