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
    private PayPalPayouts payouts;
    private PayPalConnect connect;

    @BeforeEach
    void setUp() {
        PayPalProperties properties = properties("client", "secret");
        RestClient.Builder builder = RestClient.builder().baseUrl(API);
        server = MockRestServiceServer.bindTo(builder).build();
        PayPalHttp http = new PayPalHttp(builder.build(), properties, Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC));
        checkout = new PayPalCheckout(http, properties);
        payouts = new PayPalPayouts(http);
        connect = new PayPalConnect(http, properties);
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
                .andExpect(header("PayPal-Request-Id", "create-top-up-1"))
                .andExpect(jsonPath("$.intent").value("CAPTURE"))
                .andExpect(jsonPath("$.purchase_units[0].custom_id").value("top-up-1"))
                .andExpect(jsonPath("$.purchase_units[0].amount.currency_code").value("EUR"))
                .andExpect(jsonPath("$.purchase_units[0].amount.value").value("25.00"))
                .andExpect(jsonPath("$.payment_source.paypal.experience_context.return_url").value("http://localhost:18173/consumer/guthaben/paypal"))
                .andRespond(withSuccess("""
                        {"id":"O-1","status":"PAYER_ACTION_REQUIRED","links":[
                          {"href":"https://api-m.sandbox.paypal.com/v2/checkout/orders/O-1","rel":"self"},
                          {"href":"https://www.sandbox.paypal.com/checkoutnow?token=O-1","rel":"payer-action"}]}""",
                        MediaType.APPLICATION_JSON));

        PayPalCheckout.CheckoutOrder order = checkout.createOrder("top-up-1", new PayPalAmount("EUR", new BigDecimal("25.00")), "Credit");

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
        server.expect(requestTo(API + "/v1/payments/payouts"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> payouts.send("p-1", "PAYER1", new PayPalAmount("USD", new BigDecimal("10.00")), "s", "n"))
                .isInstanceOf(PayPalUnavailableException.class);
    }

    @Test
    void payoutIsSentToThePayerIdAndItsStatusIsRead() {
        expectAppToken();
        server.expect(requestTo(API + "/v1/payments/payouts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.sender_batch_header.sender_batch_id").value("nfv-payout-7"))
                .andExpect(jsonPath("$.items[0].recipient_type").value("PAYPAL_ID"))
                .andExpect(jsonPath("$.items[0].receiver").value("PAYER1"))
                .andExpect(jsonPath("$.items[0].amount.currency").value("EUR"))
                .andExpect(jsonPath("$.items[0].amount.value").value("42.10"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"batch_header\":{\"payout_batch_id\":\"B-1\",\"batch_status\":\"PENDING\"}}"));
        server.expect(requestTo(API + "/v1/payments/payouts/B-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"batch_header":{"payout_batch_id":"B-1","batch_status":"SUCCESS"},
                         "items":[{"payout_item_id":"I-1","transaction_status":"SUCCESS"}]}""", MediaType.APPLICATION_JSON));

        PayPalPayouts.PayoutBatch sent = payouts.send("nfv-payout-7", "PAYER1",
                new PayPalAmount("EUR", new BigDecimal("42.10")), "Payout", "Earnings");
        PayPalPayouts.PayoutBatch read = payouts.getPayout("B-1");

        assertThat(sent.payoutBatchId()).isEqualTo("B-1");
        assertThat(sent.isSucceeded()).isFalse();
        assertThat(read.isSucceeded()).isTrue();
        assertThat(read.isReturned()).isFalse();
    }

    @Test
    void returnedPayoutIsRecognized() {
        assertThat(new PayPalPayouts.PayoutBatch("B-1", "SUCCESS", "RETURNED", null).isReturned()).isTrue();
        assertThat(new PayPalPayouts.PayoutBatch("B-1", "SUCCESS", "UNCLAIMED", null).isReturned()).isFalse();
        assertThat(new PayPalPayouts.PayoutBatch("B-1", "DENIED", null, null).isReturned()).isTrue();
    }

    @Test
    void connectUrlCarriesClientRedirectAndState() {
        URI url = connect.authorizationUrl("state-1");

        assertThat(url.getHost()).isEqualTo("www.sandbox.paypal.com");
        assertThat(url.getPath()).isEqualTo("/connect");
        assertThat(url.getRawQuery())
                .contains("client_id=client")
                .contains("response_type=code")
                .contains("state=state-1")
                .contains("scope=openid%20email%20https://uri.paypal.com/services/paypalattributes")
                .contains("redirect_uri=http://localhost:18173/paypal/connected");
    }

    @Test
    void connectedAccountIsReadWithTheUsersToken() {
        server.expect(requestTo(API + "/v1/identity/openidconnect/tokenservice"))
                .andExpect(header("Authorization", "Basic Y2xpZW50OnNlY3JldA=="))
                .andExpect(content().formData(form("grant_type", "authorization_code", "code", "auth-code")))
                .andRespond(withSuccess("{\"access_token\":\"user-token\",\"expires_in\":900}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/v1/oauth2/token/userinfo?schema=openid"))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andRespond(withSuccess("""
                        {"user_id":"https://www.paypal.com/webapps/auth/identity/user/abc","payer_id":"PAYER1",
                         "verified_account":"true","emails":[{"value":"old@example.com","primary":false,"confirmed":true},
                         {"value":"provider@example.com","primary":true,"confirmed":true}]}""", MediaType.APPLICATION_JSON));

        PayPalAccount account = connect.fetchAccount("auth-code");

        assertThat(account).isEqualTo(new PayPalAccount("PAYER1", "provider@example.com", true));
    }

    @Test
    void openIdUserinfoWithFlatEmailIsRead() {
        server.expect(requestTo(API + "/v1/identity/openidconnect/tokenservice"))
                .andRespond(withSuccess("{\"access_token\":\"user-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/v1/oauth2/token/userinfo?schema=openid"))
                .andRespond(withSuccess("""
                        {"user_id":"https://www.paypal.com/webapps/auth/identity/user/abc","payer_id":"PAYER2",
                         "email":"provider@example.com","email_verified":true}""", MediaType.APPLICATION_JSON));

        assertThat(connect.fetchAccount("auth-code")).isEqualTo(new PayPalAccount("PAYER2", "provider@example.com", true));
    }

    @Test
    void refusedUserinfoIsARejection() {
        server.expect(requestTo(API + "/v1/identity/openidconnect/tokenservice"))
                .andRespond(withSuccess("{\"access_token\":\"user-token\",\"scope\":\"openid\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/v1/oauth2/token/userinfo?schema=openid"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":"invalid_request","error_description":"Unable to fetch details to serve the request"}"""));

        assertThatThrownBy(() -> connect.fetchAccount("auth-code"))
                .isInstanceOfSatisfying(PayPalRejectedException.class, e -> assertThat(e.getIssue()).isEqualTo("invalid_request"));
    }

    @Test
    void withoutCredentialsNothingIsCalled() {
        PayPalProperties unconfigured = properties("client", "");
        PayPalHttp http = new PayPalHttp(RestClient.create(API), unconfigured, Clock.systemUTC());

        assertThat(new PayPalConnect(http, unconfigured).isAvailable()).isFalse();
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
        return new PayPalProperties(PayPalProperties.Environment.SANDBOX, clientId, secret,
                URI.create("http://localhost:18173/paypal/connected"),
                URI.create("http://localhost:18173/consumer/guthaben/paypal"),
                URI.create("http://localhost:18173/consumer/guthaben/paypal?abgebrochen=1"),
                "Nfv", Duration.ofSeconds(5));
    }
}
