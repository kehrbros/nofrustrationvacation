package com.vacantagent.user;

import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import com.vacantagent.user.security.AppUserAuthentication;
import com.vacantagent.user.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conversion of monetary values at the API boundary, see Docs/money.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerCurrencyTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private CurrencyConversionService currencyConversionService;

    private RequestPostProcessor user;

    @BeforeEach
    void setUp() {
        AppUser appUser = appUserProvisioning.findOrCreate("currency-user", null, null);
        jdbcTemplate.update("UPDATE app_user SET account_balance = 12.3456789, preferred_currency = NULL WHERE id = ?", appUser.getId());
        jdbcTemplate.update("DELETE FROM exchange_rate WHERE currency = 'EUR'");
        jdbcTemplate.update("INSERT INTO exchange_rate (currency, rate, rate_date, fetched_at, source) VALUES ('EUR', 0.9, ?, ?, 'TEST')",
                LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now());
        currencyConversionService.clearCache();

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("currency-user").build();
        user = authentication(new AppUserAuthentication(new CurrentUser(appUser.getId(), "currency-user"), jwt, List.of()));
    }

    @Test
    void balanceIsReturnedInBaseCurrencyByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountBalance.amount").value("12.35"))
                .andExpect(jsonPath("$.accountBalance.currency").value("USD"))
                .andExpect(jsonPath("$.accountBalance.exchangeRate").doesNotExist());
    }

    @Test
    void balanceIsConvertedToRequestedCurrency() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").param("currency", "EUR").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountBalance.amount").value("11.11"))
                .andExpect(jsonPath("$.accountBalance.currency").value("EUR"))
                .andExpect(jsonPath("$.accountBalance.exchangeRate").value("0.9000000000"))
                .andExpect(jsonPath("$.accountBalance.exchangeRateDate").value(LocalDate.now(ZoneOffset.UTC).toString()));
    }

    @Test
    void preferredCurrencyIsUsedIfNoneIsRequested() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/preferred-currency").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredCurrency").value("EUR"));

        mockMvc.perform(get("/api/v1/users/me").with(user))
                .andExpect(jsonPath("$.accountBalance.currency").value("EUR"));
    }

    @Test
    void unsupportedCurrencyIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").param("currency", "XYZ").with(user))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/users/me/preferred-currency").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"JPY\"}"))
                .andExpect(status().isBadRequest());
    }
}
