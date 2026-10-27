package com.vacantagent.llm.api;

import com.vacantagent.common.money.Money;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.llm.dto.LlmCompletion;
import com.vacantagent.llm.dto.OrderLimits;
import com.vacantagent.llm.logic.LlmRequestException;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import com.vacantagent.user.security.AppUserAuthentication;
import com.vacantagent.user.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MCP tool of the LLM proxy. LlmProxyService is mocked, the flow is covered by LlmProxyServiceTest. */
@SpringBootTest
class LlmProxyToolsTest {

    @Autowired
    private LlmProxyTools tools;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @MockitoBean
    private LlmProxyService proxyService;

    private long appUserId;

    @BeforeEach
    void setUp() {
        AppUser appUser = appUserProvisioning.findOrCreate("llm-tools-user", null, null);
        jdbcTemplate.update("UPDATE app_user SET preferred_currency = NULL WHERE id = ?", appUser.getId());
        appUserId = appUser.getId();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("llm-tools-user").build();
        SecurityContextHolder.getContext().setAuthentication(
                new AppUserAuthentication(new CurrentUser(appUserId, "llm-tools-user"), jwt, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void promptReturnsTheAnswerWithTokensAndCost() {
        when(proxyService.prompt(appUserId, "Hello", "openai", "gpt-5", null))
                .thenReturn(new LlmCompletion("Hi", 99, 1000, 500, 1500, Money.of("1.30")));

        PromptResultDto result = tools.prompt("Hello", "openai", "gpt-5", null, null, null, null);

        assertThat(result.text()).isEqualTo("Hi");
        assertThat(result.agreementId()).isEqualTo(99);
        assertThat(result.promptTokens()).isEqualTo(1000);
        assertThat(result.completionTokens()).isEqualTo(500);
        assertThat(result.totalTokens()).isEqualTo(1500);
        assertThat(result.cost().amount()).isEqualByComparingTo("1.30");
        assertThat(result.cost().amount().scale()).isEqualTo(6);
        assertThat(result.cost().currency()).isEqualTo("USD");
    }

    @Test
    void missingFiltersMeanAnyAgreement() {
        when(proxyService.prompt(appUserId, "Hello", null, null, null))
                .thenReturn(new LlmCompletion("Hi", 99, 1, 1, 2, Money.ZERO));

        assertThat(tools.prompt("Hello", null, null, null, null, null, null).text()).isEqualTo("Hi");
        verify(proxyService).prompt(appUserId, "Hello", null, null, null);
    }

    @Test
    void refusalsAreReportedWithTheReason() {
        when(proxyService.prompt(appUserId, "Hello", null, null, null))
                .thenThrow(new LlmRequestException("No active agreement for the requested model"));

        assertThatThrownBy(() -> tools.prompt("Hello", null, null, null, null, null, null))
                .hasMessage("No active agreement for the requested model");
    }

    @Test
    void toolRequiresAnAuthenticatedUser() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> tools.prompt("Hello", null, null, null, null, null, null)).hasMessage("No authenticated user");
        verify(proxyService, never()).prompt(anyLong(), any(), any(), any(), any());
    }

    @Test
    void maxCostsBecomeTheOrderLimits() {
        when(proxyService.prompt(appUserId, "Hello", "claude", null,
                new OrderLimits(new MoneyDto(new BigDecimal("1.00"), "USD"),
                        new MoneyDto(new BigDecimal("5.00"), "USD"), Duration.ofSeconds(30))))
                .thenReturn(new LlmCompletion("Hi", 99, 1, 1, 2, Money.ZERO));

        assertThat(tools.prompt("Hello", "claude", null, "1.00", "5.00", "USD", null).text()).isEqualTo("Hi");
    }

    @Test
    void orderLimitsAreRequiredTogether() {
        assertThatThrownBy(() -> tools.prompt("Hello", null, null, "1.00", null, "USD", null))
                .isInstanceOf(LlmRequestException.class);
        assertThatThrownBy(() -> tools.prompt("Hello", null, null, "1.00", "5.00", "USD", "PT2M"))
                .hasMessage("marketWaitTime must be between PT0S and PT1M");
        verify(proxyService, never()).prompt(anyLong(), any(), any(), any(), any());
    }
}