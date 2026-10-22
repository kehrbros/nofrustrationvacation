package com.vacantagent.accounting;

import com.vacantagent.accounting.data.PayoutAccountRepository;
import com.vacantagent.accounting.data.PayoutRepository;
import com.vacantagent.accounting.entities.PayoutAccount;
import com.vacantagent.accounting.entities.PayoutStatus;
import com.vacantagent.accounting.logic.InsufficientBalanceException;
import com.vacantagent.accounting.logic.PayoutLogic;
import com.vacantagent.accounting.logic.TopUpLogic;
import com.vacantagent.accounting.logic.UsageBilling;
import com.vacantagent.common.money.Money;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.paypal.PayPalAccount;
import com.vacantagent.paypal.PayPalAmount;
import com.vacantagent.paypal.PayPalCheckout;
import com.vacantagent.paypal.PayPalConnect;
import com.vacantagent.paypal.PayPalPayouts;
import com.vacantagent.paypal.PayPalRejectedException;
import com.vacantagent.paypal.PayPalUnavailableException;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import com.vacantagent.user.security.AppUserAuthentication;
import com.vacantagent.user.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Top-ups, payouts and linking of the payout account through the API, with PayPal mocked. See Docs/accounting.md.
 * EUR rate in all tests: 0.9 EUR per USD.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountingFlowTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private CurrencyConversionService currencyConversionService;
    @Autowired
    private PayoutAccountRepository payoutAccountRepository;
    @Autowired
    private PayoutRepository payoutRepository;
    @Autowired
    private PayoutLogic payoutLogic;
    @Autowired
    private UsageBilling usageBilling;
    @Autowired
    private TopUpLogic topUpLogic;

    @MockitoBean
    private PayPalCheckout checkout;
    @MockitoBean
    private PayPalPayouts payPalPayouts;
    @MockitoBean
    private PayPalConnect connect;

    private long appUserId;
    private RequestPostProcessor user;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM exchange_rate WHERE currency = 'EUR'");
        jdbcTemplate.update("INSERT INTO exchange_rate (currency, rate, rate_date, fetched_at, source) VALUES ('EUR', 0.9, ?, ?, 'TEST')",
                LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now());
        currencyConversionService.clearCache();
        appUserId = newUser();
        user = as(appUserId);
        when(connect.isAvailable()).thenReturn(true);
    }

    // --- top-ups

    @Test
    void topUpInEurIsCreditedInBaseCurrencyExactlyOnce() throws Exception {
        when(checkout.createOrder(anyString(), any(), anyString()))
                .thenReturn(new PayPalCheckout.CheckoutOrder("O-1", "https://www.sandbox.paypal.com/checkoutnow?token=O-1"));
        when(checkout.capture(eq("O-1"), anyString())).thenReturn(captured("O-1", "EUR", "18.00"));

        perform(post("/api/v1/accounting/top-ups"), "{\"amount\":{\"amount\":\"18\",\"currency\":\"EUR\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approveUrl").value("https://www.sandbox.paypal.com/checkoutnow?token=O-1"))
                .andExpect(jsonPath("$.topUp.status").value("CREATED"))
                .andExpect(jsonPath("$.topUp.paid.amount").value("18.00"))
                .andExpect(jsonPath("$.topUp.paid.exchangeRate").value("0.9000000000"));
        ArgumentCaptor<PayPalAmount> amount = ArgumentCaptor.forClass(PayPalAmount.class);
        verify(checkout).createOrder(anyString(), amount.capture(), anyString());
        assertThat(amount.getValue()).isEqualTo(new PayPalAmount("EUR", new BigDecimal("18.00")));

        perform(post("/api/v1/accounting/top-ups/paypal/O-1/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        perform(post("/api/v1/accounting/top-ups/paypal/O-1/capture"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(checkout, times(1)).capture(eq("O-1"), anyString());
        perform(get("/api/v1/accounting/balances").param("currency", "USD"))
                .andExpect(jsonPath("$.credit.amount").value("20.00"))
                .andExpect(jsonPath("$.earnings.amount").value("0.00"));
        perform(get("/api/v1/accounting/entries").param("currency", "USD"))
                .andExpect(jsonPath("$[0].type").value("TOP_UP"))
                .andExpect(jsonPath("$[0].account").value("CREDIT"))
                .andExpect(jsonPath("$[0].amount.amount").value("20.00"));
    }

    @Test
    void declinedPaymentIsNotCredited() throws Exception {
        startTopUp("O-2");
        when(checkout.capture(eq("O-2"), anyString()))
                .thenThrow(new PayPalRejectedException(422, "INSTRUMENT_DECLINED", "d", "declined"));

        perform(post("/api/v1/accounting/top-ups/paypal/O-2/capture"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));

        perform(get("/api/v1/accounting/top-ups")).andExpect(jsonPath("$[0].status").value("FAILED"));
        perform(get("/api/v1/accounting/balances")).andExpect(jsonPath("$.credit.amount").value("0.00"));
    }

    @Test
    void pendingCaptureIsCreditedOnceCompleted() throws Exception {
        startTopUp("O-3");
        when(checkout.capture(eq("O-3"), anyString())).thenReturn(new PayPalCheckout.OrderState("O-3", "COMPLETED", "C-3", "PENDING",
                new PayPalAmount("USD", new BigDecimal("20.00"))));
        perform(post("/api/v1/accounting/top-ups/paypal/O-3/capture")).andExpect(jsonPath("$.status").value("PENDING"));
        perform(get("/api/v1/accounting/balances")).andExpect(jsonPath("$.credit.amount").value("0.00"));

        when(checkout.getOrder("O-3")).thenReturn(captured("O-3", "USD", "20.00"));
        topUpLogic.checkPending();

        perform(get("/api/v1/accounting/balances")).andExpect(jsonPath("$.credit.amount").value("20.00"));
    }

    @Test
    void cancelledTopUpStaysUncredited() throws Exception {
        startTopUp("O-4");

        perform(post("/api/v1/accounting/top-ups/paypal/O-4/cancel")).andExpect(jsonPath("$.status").value("CANCELLED"));
        perform(post("/api/v1/accounting/top-ups/paypal/O-4/capture")).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(checkout, never()).capture(anyString(), anyString());
    }

    @Test
    void topUpOfAnotherUserIsNotFound() throws Exception {
        startTopUp("O-5");

        mockMvc.perform(post("/api/v1/accounting/top-ups/paypal/O-5/capture").with(as(newUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOP_UP_NOT_FOUND"));
    }

    @Test
    void invalidTopUpAmountsAreRefused() throws Exception {
        perform(post("/api/v1/accounting/top-ups"), "{\"amount\":{\"amount\":\"1.00\",\"currency\":\"USD\"}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AMOUNT_OUT_OF_RANGE"));
        perform(post("/api/v1/accounting/top-ups"), "{\"amount\":{\"amount\":\"10.001\",\"currency\":\"USD\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        perform(post("/api/v1/accounting/top-ups"), "{\"amount\":{\"amount\":\"-10\",\"currency\":\"USD\"}}")
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/accounting/top-ups"), "{\"amount\":{\"amount\":\"10\",\"currency\":\"JPY\"}}")
                .andExpect(status().isBadRequest());
        verify(checkout, never()).createOrder(anyString(), any(), anyString());
    }

    // --- payout account

    @Test
    void payPalAccountIsLinkedWithTheStateOfTheSameUser() throws Exception {
        String state = startLink(user);
        when(connect.fetchAccount("code-1")).thenReturn(new PayPalAccount("PAYER-" + appUserId, "p@example.com", true));

        perform(get("/api/v1/accounting/payout-account")).andExpect(jsonPath("$.linked").value(false));
        perform(post("/api/v1/accounting/payout-account/paypal"), link("code-1", state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.email").value("p@example.com"))
                .andExpect(jsonPath("$.verified").value(true));
        perform(get("/api/v1/accounting/payout-account"))
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.payPalAvailable").value(true));

        // a state is valid once
        perform(post("/api/v1/accounting/payout-account/paypal"), link("code-1", state))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_STATE_INVALID"));
    }

    @Test
    void stateOfAnotherUserIsRefused() throws Exception {
        String foreignState = startLink(as(newUser()));

        perform(post("/api/v1/accounting/payout-account/paypal"), link("code-1", foreignState))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_STATE_INVALID"));
        verify(connect, never()).fetchAccount(anyString());
    }

    @Test
    void payPalAccountCanBeLinkedToOneUserOnly() throws Exception {
        String payerId = "PAYER-shared-" + appUserId;
        linkDirectly(appUserId, payerId);
        long otherUserId = newUser();
        RequestPostProcessor other = as(otherUserId);
        String state = startLink(other);
        when(connect.fetchAccount("code-2")).thenReturn(new PayPalAccount(payerId, null, true));

        mockMvc.perform(json(post("/api/v1/accounting/payout-account/paypal")).content(link("code-2", state)).with(other))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYPAL_ACCOUNT_IN_USE"));
    }

    // --- payouts

    @Test
    void payoutWithoutLinkedAccountIsRefused() throws Exception {
        perform(post("/api/v1/accounting/payouts"), "{\"currency\":\"USD\",\"amount\":\"20.00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYOUT_ACCOUNT_REQUIRED"));
    }

    @Test
    void allEarningsArePaidOutRoundedDownAndCompleteLater() throws Exception {
        earn(appUserId, "33.3333");
        linkDirectly(appUserId, "PAYER-" + appUserId);
        when(payPalPayouts.send(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new PayPalPayouts.PayoutBatch("B-1", "PENDING", null, null));

        perform(get("/api/v1/accounting/balances").param("currency", "EUR"))
                .andExpect(jsonPath("$.earnings.amount").value("29.99"));
        perform(post("/api/v1/accounting/payouts"), "{\"currency\":\"EUR\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.paidOut.amount").value("29.99"))
                .andExpect(jsonPath("$.paidOut.currency").value("EUR"));

        ArgumentCaptor<PayPalAmount> amount = ArgumentCaptor.forClass(PayPalAmount.class);
        verify(payPalPayouts).send(anyString(), eq("PAYER-" + appUserId), amount.capture(), anyString(), anyString());
        assertThat(amount.getValue()).isEqualTo(new PayPalAmount("EUR", new BigDecimal("29.99")));
        // 33.3333 - 29.99 / 0.9 = 0.0111: what is rounded away in EUR stays as earnings
        perform(get("/api/v1/accounting/balances").param("currency", "USD"))
                .andExpect(jsonPath("$.earnings.amount").value("0.01"));

        when(payPalPayouts.getPayout("B-1")).thenReturn(new PayPalPayouts.PayoutBatch("B-1", "SUCCESS", "SUCCESS", null));
        payoutLogic.checkSubmitted();
        perform(get("/api/v1/accounting/payouts")).andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    void refusedPayoutIsBookedBack() throws Exception {
        earn(appUserId, "50");
        linkDirectly(appUserId, "PAYER-" + appUserId);
        when(payPalPayouts.send(anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new PayPalRejectedException(422, "INSUFFICIENT_FUNDS", "d", "no funds"));

        perform(post("/api/v1/accounting/payouts"), "{\"currency\":\"USD\",\"amount\":\"20\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"));

        perform(get("/api/v1/accounting/balances")).andExpect(jsonPath("$.earnings.amount").value("50.00"));
        perform(get("/api/v1/accounting/entries"))
                .andExpect(jsonPath("$[0].type").value("PAYOUT_REVERSAL"))
                .andExpect(jsonPath("$[0].amount.amount").value("20.00"))
                .andExpect(jsonPath("$[1].type").value("PAYOUT"))
                .andExpect(jsonPath("$[1].amount.amount").value("-20.00"));
    }

    @Test
    void returnedPayoutIsBookedBack() throws Exception {
        earn(appUserId, "50");
        linkDirectly(appUserId, "PAYER-" + appUserId);
        when(payPalPayouts.send(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new PayPalPayouts.PayoutBatch("B-2", "PENDING", null, null));
        perform(post("/api/v1/accounting/payouts"), "{\"currency\":\"USD\",\"amount\":\"50\"}")
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        when(payPalPayouts.getPayout("B-2")).thenReturn(new PayPalPayouts.PayoutBatch("B-2", "SUCCESS", "RETURNED", null));
        payoutLogic.checkSubmitted();
        payoutLogic.checkSubmitted();

        perform(get("/api/v1/accounting/payouts")).andExpect(jsonPath("$[0].status").value("FAILED"));
        perform(get("/api/v1/accounting/balances")).andExpect(jsonPath("$.earnings.amount").value("50.00"));
    }

    @Test
    void payoutWithUnknownOutcomeStaysDebitedAndBlocksUnlinking() throws Exception {
        earn(appUserId, "50");
        linkDirectly(appUserId, "PAYER-" + appUserId);
        when(payPalPayouts.send(anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new PayPalUnavailableException("timeout"));

        perform(post("/api/v1/accounting/payouts"), "{\"currency\":\"USD\",\"amount\":\"30\"}")
                .andExpect(jsonPath("$.status").value("UNKNOWN"));

        perform(get("/api/v1/accounting/balances")).andExpect(jsonPath("$.earnings.amount").value("20.00"));
        perform(delete("/api/v1/accounting/payout-account"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYOUTS_OPEN"));
        assertThat(payoutRepository.findByAppUserIdOrderByRequestedAtDescIdDesc(appUserId,
                Pageable.unpaged()).getFirst().getStatus()).isEqualTo(PayoutStatus.UNKNOWN);
    }

    @Test
    void payoutAboveEarningsOrBelowMinimumIsRefused() throws Exception {
        earn(appUserId, "15");
        linkDirectly(appUserId, "PAYER-" + appUserId);

        perform(post("/api/v1/accounting/payouts"), "{\"currency\":\"USD\",\"amount\":\"15.01\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        perform(post("/api/v1/accounting/payouts"), "{\"currency\":\"USD\",\"amount\":\"5\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AMOUNT_OUT_OF_RANGE"));
        verify(payPalPayouts, never()).send(anyString(), anyString(), any(), anyString(), anyString());
        perform(get("/api/v1/accounting/balances")).andExpect(jsonPath("$.earnings.amount").value("15.00"));
    }

    // --- usage

    @Test
    void usageMovesCreditToEarningsOrNothing() throws Exception {
        long consumer = newUser();
        credit(consumer, "1.00");

        usageBilling.settle(1L, consumer, appUserId, Money.of("0.75"));
        assertThatThrownBy(() -> usageBilling.settle(2L, consumer, appUserId, Money.of("0.30")))
                .isInstanceOf(InsufficientBalanceException.class);

        perform(get("/api/v1/accounting/balances").param("currency", "USD"))
                .andExpect(jsonPath("$.earnings.amount").value("0.75"))
                .andExpect(jsonPath("$.credit.amount").value("0.00"));
        mockMvc.perform(get("/api/v1/accounting/balances").param("currency", "USD").with(as(consumer)))
                .andExpect(jsonPath("$.credit.amount").value("0.25"));
    }

    @Test
    void balancesAreConvertedToTheRequestedCurrency() throws Exception {
        credit(appUserId, "12.3456789");

        perform(get("/api/v1/accounting/balances"))
                .andExpect(jsonPath("$.credit.amount").value("12.35"))
                .andExpect(jsonPath("$.credit.currency").value("USD"))
                .andExpect(jsonPath("$.credit.exchangeRate").doesNotExist());
        perform(get("/api/v1/accounting/balances").param("currency", "EUR"))
                .andExpect(jsonPath("$.credit.amount").value("11.11"))
                .andExpect(jsonPath("$.credit.currency").value("EUR"))
                .andExpect(jsonPath("$.credit.exchangeRate").value("0.9000000000"));
        perform(get("/api/v1/accounting/balances").param("currency", "XYZ")).andExpect(status().isBadRequest());
    }

    // --- helpers

    private void startTopUp(String orderId) throws Exception {
        when(checkout.createOrder(anyString(), any(), anyString()))
                .thenReturn(new PayPalCheckout.CheckoutOrder(orderId, "https://paypal.test/" + orderId));
        perform(post("/api/v1/accounting/top-ups"), "{\"amount\":{\"amount\":\"20.00\",\"currency\":\"USD\"}}")
                .andExpect(status().isCreated());
    }

    private static PayPalCheckout.OrderState captured(String orderId, String currency, String value) {
        return new PayPalCheckout.OrderState(orderId, "COMPLETED", "C-" + orderId, "COMPLETED",
                new PayPalAmount(currency, new BigDecimal(value)));
    }

    private String startLink(RequestPostProcessor who) throws Exception {
        when(connect.authorizationUrl(anyString()))
                .thenAnswer(invocation -> URI.create("https://www.sandbox.paypal.com/connect?state=" + invocation.getArgument(0)));
        String url = mockMvc.perform(post("/api/v1/accounting/payout-account/paypal/authorization").with(who))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return url.substring(url.indexOf("state=") + 6, url.lastIndexOf('"'));
    }

    private static String link(String code, String state) {
        return "{\"code\":\"" + code + "\",\"state\":\"" + state + "\"}";
    }

    private void linkDirectly(long userId, String payerId) {
        payoutAccountRepository.save(new PayoutAccount(userId, PayoutAccount.PAYPAL, payerId, null, true, OffsetDateTime.now()));
    }

    private void earn(long userId, String amount) {
        long consumer = newUser();
        credit(consumer, amount);
        usageBilling.settle(99L, consumer, userId, Money.of(amount));
    }

    private void credit(long userId, String amount) {
        jdbcTemplate.update("INSERT INTO accounting_account (app_user_id, type, balance, created_at) VALUES (?, 'CREDIT', ?, CURRENT_TIMESTAMP)",
                userId, new BigDecimal(amount));
    }

    private long newUser() {
        String username = "accounting-" + UUID.randomUUID();
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        return appUser.getId();
    }

    private static RequestPostProcessor as(long appUserId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-" + appUserId).build();
        return authentication(new AppUserAuthentication(new CurrentUser(appUserId, "user-" + appUserId), jwt, List.of()));
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(json(builder).with(user));
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(json(builder).content(body).with(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON);
    }
}
