package com.nfv.paypal;

import com.nfv.paypal.client.PayPalHttp;
import com.nfv.paypal.config.PayPalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Requests to and responses from the PayPal REST API, against a mocked server. */
class PayPalClientTest {
    private static final String API = "https://api-m.sandbox.paypal.com";
    private static final String APP_TOKEN = "{\"access_token\":\"app-token\",\"expires_in\":32400}";

    private MockRestServiceServer server;
    private PayPalCheckout checkout;

    @BeforeEach
    void setUp() {
        PayPalProperties properties = properties("client", "secret");
        RestClient.Builder builder = RestClient.builder().baseUrl(API);
        server = MockRestServiceServer.bindTo(builder).build();
        PayPalHttp http = new PayPalHttp(builder.build(), properties, Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC));
        checkout = new PayPalCheckout(http, properties);
    }

    @Test
    void appTokenIsFetchedOnceAndReused() {
        server.expect(once(), requestTo(API + "/v1/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic Y2xpZW50OnNlY3JldA=="))
                .andExpect(content().formData(form("grant_type", "client_credentials")))
                .andRespond(withSuccess(APP_TOKEN, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/v2/checkout/orders/O-1"))
                .andExpect(header("Authorization", "Bearer app-token"))
                .andRespond(withSuccess(order("O-1", "APPROVED", null), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/v2/checkout/orders/O-1"))
                .andExpect(header("Authorization", "Bearer app-token"))
                .andRespond(withSuccess(order("O-1", "APPROVED", null), MediaType.APPLICATION_JSON));

        checkout.getOrder("O-1");
        PayPalCheckout.OrderState state = checkout.getOrder("O-1");

        assertThat(state.status()).isEqualTo("APPROVED");
        assertThat(state.captureId()).isNull();
        server.verify();
    }

    @Test
    void orderIsCreatedIdempotentlyWithApprovalLink() {
        expectAppToken();
        server.expect(requestTo(API + "/v2/checkout/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PayPal-Request-Id", "create-payment-1"))
                .andExpect(jsonPath("$.intent").value("CAPTURE"))
                .andExpect(jsonPath("$.purchase_units[0].custom_id").value("payment-1"))
                .andExpect(jsonPath("$.purchase_units[0].amount.currency_code").value("EUR"))
                .andExpect(jsonPath("$.purchase_units[0].amount.value").value("25.00"))
                .andExpect(jsonPath("$.payment_source.paypal.experience_context.return_url").value("http://localhost:18173/paypal/return"))
                .andRespond(withSuccess("""
                        {"id":"O-1","status":"PAYER_ACTION_REQUIRED","links":[
                          {"href":"https://api-m.sandbox.paypal.com/v2/checkout/orders/O-1","rel":"self"},
                          {"href":"https://www.sandbox.paypal.com/checkoutnow?token=O-1","rel":"payer-action"}]}""",
                        MediaType.APPLICATION_JSON));

        PayPalCheckout.CheckoutOrder order = checkout.createOrder("payment-1", new PayPalAmount("EUR", new BigDecimal("25.00")), "Hotel",
                PayPalCheckout.Intent.CAPTURE, URI.create("http://localhost:18173/paypal/return"),
                URI.create("http://localhost:18173/paypal/cancel"));

        assertThat(order.orderId()).isEqualTo("O-1");
        assertThat(order.approveUrl()).isEqualTo("https://www.sandbox.paypal.com/checkoutnow?token=O-1");
        server.verify();
    }

    @Test
    void authorizeOrderHasItsOwnReturnUrl() {
        expectAppToken();
        server.expect(requestTo(API + "/v2/checkout/orders"))
                .andExpect(jsonPath("$.intent").value("AUTHORIZE"))
                .andExpect(jsonPath("$.payment_source.paypal.experience_context.return_url")
                        .value("http://localhost:18173/reisen/1/buchungen/2/alternativen?reservierung=3"))
                .andRespond(withSuccess("""
                        {"id":"O-2","status":"PAYER_ACTION_REQUIRED","links":[
                          {"href":"https://www.sandbox.paypal.com/checkoutnow?token=O-2","rel":"payer-action"}]}""",
                        MediaType.APPLICATION_JSON));

        PayPalCheckout.CheckoutOrder order = checkout.createOrder("stay-3", new PayPalAmount("EUR", new BigDecimal("180.00")),
                "Hotel", PayPalCheckout.Intent.AUTHORIZE,
                URI.create("http://localhost:18173/reisen/1/buchungen/2/alternativen?reservierung=3"),
                URI.create("http://localhost:18173/reisen/1/buchungen/2/alternativen?abgebrochen=1"));

        assertThat(order.orderId()).isEqualTo("O-2");
        server.verify();
    }

    @Test
    void authorizationIsCapturedOrVoidedAndCapturesRefunded() {
        expectAppToken();
        server.expect(requestTo(API + "/v2/checkout/orders/O-2/authorize"))
                .andExpect(header("PayPal-Request-Id", "authorize-3"))
                .andRespond(withSuccess("""
                        {"id":"O-2","status":"COMPLETED","purchase_units":[{"payments":{"authorizations":[
                          {"id":"A-1","status":"CREATED","amount":{"currency_code":"EUR","value":"180.00"}}]}}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/v2/payments/authorizations/A-1/capture"))
                .andExpect(jsonPath("$.final_capture").value(true))
                .andRespond(withSuccess("{\"id\":\"C-9\",\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/v2/payments/authorizations/A-1/void"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"name":"UNPROCESSABLE_ENTITY","details":[{"issue":"AUTHORIZATION_ALREADY_VOIDED"}]}"""));
        server.expect(requestTo(API + "/v2/payments/captures/C-9/refund"))
                .andRespond(withSuccess("{\"id\":\"R-1\",\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));

        PayPalCheckout.Payment authorization = checkout.authorize("O-2", "authorize-3");
        assertThat(authorization.id()).isEqualTo("A-1");
        assertThat(authorization.amount()).isEqualTo(new PayPalAmount("EUR", new BigDecimal("180.00")));
        assertThat(checkout.captureAuthorization("A-1", "capture-3").isCompleted()).isTrue();
        checkout.voidAuthorization("A-1", "void-3");
        assertThat(checkout.refundCapture("C-9", "refund-3").isCompleted()).isTrue();
        server.verify();
    }

    @Test
    void captureReturnsTheCapturedAmount() {
        expectAppToken();
        server.expect(requestTo(API + "/v2/checkout/orders/O-1/capture"))
                .andExpect(header("PayPal-Request-Id", "capture-1"))
                .andRespond(withSuccess(order("O-1", "COMPLETED", "COMPLETED"), MediaType.APPLICATION_JSON));

        PayPalCheckout.OrderState state = checkout.capture("O-1", "capture-1");

        assertThat(state.isCaptureCompleted()).isTrue();
        assertThat(state.captureId()).isEqualTo("C-1");
        assertThat(state.capturedAmount()).isEqualTo(new PayPalAmount("EUR", new BigDecimal("25.00")));
    }

    @Test
    void captureOfAnAlreadyCapturedOrderReadsTheOrder() {
        expectAppToken();
        server.expect(requestTo(API + "/v2/checkout/orders/O-1/capture"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"name":"UNPROCESSABLE_ENTITY","details":[{"issue":"ORDER_ALREADY_CAPTURED"}],"debug_id":"d1"}"""));
        server.expect(requestTo(API + "/v2/checkout/orders/O-1"))
                .andRespond(withSuccess(order("O-1", "COMPLETED", "COMPLETED"), MediaType.APPLICATION_JSON));

        assertThat(checkout.capture("O-1", "capture-1").isCaptureCompleted()).isTrue();
    }

    @Test
    void clientErrorsAreRejectionsWithIssue() {
        expectAppToken();
        server.expect(requestTo(API + "/v2/checkout/orders/O-1/capture"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"name":"UNPROCESSABLE_ENTITY","details":[{"issue":"INSTRUMENT_DECLINED"}],"debug_id":"d2"}"""));

        assertThatThrownBy(() -> checkout.capture("O-1", "capture-1"))
                .isInstanceOfSatisfying(PayPalRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getIssue()).isEqualTo("INSTRUMENT_DECLINED");
                    assertThat(e.getDebugId()).isEqualTo("d2");
                });
    }

    @Test
    void serverErrorsMeanUnknownOutcome() {
        expectAppToken();
        server.expect(requestTo(API + "/v2/payments/authorizations/A-1/capture"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> checkout.captureAuthorization("A-1", "capture-a-1"))
                .isInstanceOf(PayPalUnavailableException.class);
    }

    @Test
    void withoutCredentialsNothingIsCalled() {
        PayPalProperties unconfigured = properties("client", "");
        PayPalHttp http = new PayPalHttp(RestClient.create(API), unconfigured, Clock.systemUTC());

        assertThatThrownBy(() -> new PayPalCheckout(http, unconfigured).getOrder("O-1"))
                .isInstanceOf(PayPalNotConfiguredException.class);
    }

    @Test
    void secretIsNotPrinted() {
        assertThat(properties("client", "very-secret").toString()).doesNotContain("very-secret").contains("<redacted>");
    }

    private void expectAppToken() {
        server.expect(requestTo(API + "/v1/oauth2/token")).andRespond(withSuccess(APP_TOKEN, MediaType.APPLICATION_JSON));
    }

    private static String order(String id, String status, String captureStatus) {
        String captures = captureStatus == null ? "" : """
                ,"purchase_units":[{"payments":{"captures":[{"id":"C-1","status":"%s",
                  "amount":{"currency_code":"EUR","value":"25.00"}}]}}]""".formatted(captureStatus);
        return "{\"id\":\"" + id + "\",\"status\":\"" + status + "\"" + captures + "}";
    }

    private static LinkedMultiValueMap<String, String> form(String... keyValues) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            form.add(keyValues[i], keyValues[i + 1]);
        }
        return form;
    }

    private static PayPalProperties properties(String clientId, String secret) {
        return new PayPalProperties(PayPalProperties.Environment.SANDBOX, clientId, secret, "Nfv", Duration.ofSeconds(5));
    }
}
