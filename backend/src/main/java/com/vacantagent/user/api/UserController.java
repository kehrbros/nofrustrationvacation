package com.vacantagent.user.api;

import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.currency.logic.MoneyRounding;
import com.vacantagent.user.dto.AppUserDto;
import com.vacantagent.user.dto.PreferredCurrencyDto;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserSettings;
import com.vacantagent.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Currency;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final AppUserSettings appUserSettings;
    private final CurrencyConversionService currencyConversionService;

    @GetMapping("/me")
    public AppUserDto getProfile(@AuthenticationPrincipal CurrentUser currentUser,
                                 @Parameter(description = "ISO 4217 code of the currency for monetary values, defaults to the preferred currency")
                                 @RequestParam(required = false) @Nullable String currency) {
        AppUser user = appUserSettings.get(currentUser.appUserId());
        return toDto(user, currency);
    }

    @PutMapping("/me/preferred-currency")
    public AppUserDto changePreferredCurrency(@AuthenticationPrincipal CurrentUser currentUser,
                                             @RequestBody PreferredCurrencyDto preferredCurrency) {
        String code = preferredCurrency.currency();
        if (code != null) {
            currencyConversionService.supportedCurrency(code);
        }
        AppUser user = appUserSettings.changePreferredCurrency(currentUser.appUserId(), code);
        return toDto(user, null);
    }

    private AppUserDto toDto(AppUser user, @Nullable String requestedCurrency) {
        Currency currency = currencyConversionService.responseCurrency(requestedCurrency, user.getPreferredCurrency());
        return new AppUserDto(user.getId(), user.getUsername(), user.getPreferredCurrency(),
                currencyConversionService.toDto(user.getAccountBalance(), currency, MoneyRounding.AMOUNT));
    }
}
