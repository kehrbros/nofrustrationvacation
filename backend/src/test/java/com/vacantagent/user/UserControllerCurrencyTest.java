package com.vacantagent.user;

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

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preferred currency of API responses, see Docs/money.md. Conversion of balances: {@code AccountingControllerTest}.
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

    private RequestPostProcessor user;

    @BeforeEach
    void setUp() {
        AppUser appUser = appUserProvisioning.findOrCreate("currency-user", null, null);
        jdbcTemplate.update("UPDATE app_user SET preferred_currency = NULL WHERE id = ?", appUser.getId());

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("currency-user").build();
        user = authentication(new AppUserAuthentication(new CurrentUser(appUser.getId(), "currency-user"), jwt, List.of()));
    }

    @Test
    void preferredCurrencyIsStored() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/preferred-currency").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredCurrency").value("EUR"));

        mockMvc.perform(get("/api/v1/users/me").with(user))
                .andExpect(jsonPath("$.preferredCurrency").value("EUR"))
                .andExpect(jsonPath("$.accountBalance").doesNotExist());
    }

    @Test
    void unsupportedCurrencyIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/preferred-currency").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"JPY\"}"))
                .andExpect(status().isBadRequest());
    }
}
