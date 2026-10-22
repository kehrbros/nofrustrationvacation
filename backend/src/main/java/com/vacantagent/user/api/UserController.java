package com.vacantagent.user.api;

import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.user.dto.AppUserDto;
import com.vacantagent.user.dto.PreferredCurrencyDto;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserSettings;
import com.vacantagent.user.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final AppUserSettings appUserSettings;
    private final CurrencyConversionService currencyConversionService;

    @GetMapping("/me")
    public AppUserDto getProfile(@AuthenticationPrincipal CurrentUser currentUser) {
        return toDto(appUserSettings.get(currentUser.appUserId()));
    }

    @PutMapping("/me/preferred-currency")
    public AppUserDto changePreferredCurrency(@AuthenticationPrincipal CurrentUser currentUser,
                                             @RequestBody PreferredCurrencyDto preferredCurrency) {
        String code = preferredCurrency.currency();
        if (code != null) {
            currencyConversionService.supportedCurrency(code);
        }
        AppUser user = appUserSettings.changePreferredCurrency(currentUser.appUserId(), code);
        return toDto(user);
    }

    private static AppUserDto toDto(AppUser user) {
        return new AppUserDto(user.getId(), user.getUsername(), user.getPreferredCurrency());
    }
}
