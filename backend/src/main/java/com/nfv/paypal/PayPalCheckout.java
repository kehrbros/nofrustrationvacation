package com.nfv.paypal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nfv.paypal.client.PayPalHttp;
import com.nfv.paypal.config.PayPalProperties;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Payments of payers to the platform with the Orders API v2: create an order, the payer approves it at PayPal, then
 * capture it (intent {@code CAPTURE}), or authorize it and capture or void the authorization later (intent
 * {@code AUTHORIZE}), see Docs/paypal.md.
 */
@Component
@RequiredArgsConstructor
public class PayPalCheckout {
    private final PayPalHttp http;
    private final PayPalProperties properties;

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** @throws PayPalNotConfiguredException without client id and secret */
    public void requireConfigured() {
        http.requireConfigured();
    }

    /**
     * @param reference our id of the payment, stored as {@code custom_id} and used for idempotency: repeating the
     *                  call with the same reference returns the same order
     * @return the order, the payer has to be sent to {@link CheckoutOrder#approveUrl()}
     */
    public CheckoutOrder createOrder(String reference, PayPalAmount amount, String description) {
        return createOrder(reference, amount, description, Intent.CAPTURE, properties.checkoutReturnUrl(),
                properties.checkoutCancelUrl());
    }

    /**
     * Order with its own return and cancel URL. With {@link Intent#AUTHORIZE} the money is only reserved after
     * approval ({@link #authorize}) and moved later ({@link #captureAuthorization}) or released ({@link #voidAuthorization}).
     */
    public CheckoutOrder createOrder(String reference, PayPalAmount amount, String description, Intent intent,
                                     URI returnUrl, URI cancelUrl) {
        http.requireConfigured();
        var body = Map.of(
                "intent", intent.name(),
                "purchase_units", List.of(Map.of(
                        "reference_id", reference,
                        "custom_id", reference,
                        "description", description,
                        "amount", new OrderAmount(amount.currency(), amount.formattedValue()))),
                "payment_source", Map.of("paypal", Map.of("experience_context", Map.of(
                        "brand_name", properties.brandName(),
                        "user_action", "PAY_NOW",
                        "shipping_preference", "NO_SHIPPING",
                        "return_url", returnUrl.toString(),
                        "cancel_url", cancelUrl.toString()))));
        OrderResponse order = http.post("/v2/checkout/orders", body, OrderResponse.class, "create-" + reference);
        String approveUrl = PayPalHttp.Link.find(order.links(), "payer-action");
        if (approveUrl == null) {
            approveUrl = PayPalHttp.Link.find(order.links(), "approve");
        }
        if (approveUrl == null) {
            throw new PayPalUnavailableException("PayPal order " + order.id() + " has no approval link");
        }
        return new CheckoutOrder(order.id(), approveUrl);
    }

    /**
     * Captures an approved order. Repeating with the same {@code requestId} returns the first result, an order
     * captured before is read instead.
     *
     * @throws PayPalRejectedException e.g. {@code ORDER_NOT_APPROVED} or {@code INSTRUMENT_DECLINED}
     */
    public OrderState capture(String orderId, String requestId) {
        http.requireConfigured();
        try {
            return toState(http.post("/v2/checkout/orders/" + orderId + "/capture", Map.of(), OrderResponse.class, requestId));
        } catch (PayPalRejectedException e) {
            if ("ORDER_ALREADY_CAPTURED".equals(e.getIssue())) {
                return getOrder(orderId);
            }
            throw e;
        }
    }

    public OrderState getOrder(String orderId) {
        http.requireConfigured();
        return toState(http.get("/v2/checkout/orders/" + orderId, OrderResponse.class));
    }

    /**
     * Authorizes an approved order of intent {@code AUTHORIZE}: the money is reserved, not moved. An order authorized
     * before is read instead.
     *
     * @throws PayPalRejectedException e.g. {@code ORDER_NOT_APPROVED} or {@code INSTRUMENT_DECLINED}
     */
    public Payment authorize(String orderId, String requestId) {
        http.requireConfigured();
        OrderResponse order;
        try {
            order = http.post("/v2/checkout/orders/" + orderId + "/authorize", Map.of(), OrderResponse.class, requestId);
        } catch (PayPalRejectedException e) {
            if (!"ORDER_ALREADY_AUTHORIZED".equals(e.getIssue())) {
                throw e;
            }
            order = http.get("/v2/checkout/orders/" + orderId, OrderResponse.class);
        }
        PaymentResponse authorization = order.firstAuthorization();
        if (authorization == null) {
            throw new PayPalUnavailableException("PayPal order " + orderId + " has no authorization");
        }
        return authorization.toPayment();
    }

    /**
     * Moves the authorized money (final capture). Repeating with the same {@code requestId} returns the first result.
     *
     * @return the capture, {@code COMPLETED} or {@code PENDING} means the money is (or will be) there
     */
    public Payment captureAuthorization(String authorizationId, String requestId) {
        http.requireConfigured();
        return http.post("/v2/payments/authorizations/" + authorizationId + "/capture", Map.of("final_capture", true),
                PaymentResponse.class, requestId).toPayment();
    }

    /** Releases an authorization without moving money, e.g. when the booked service could not be delivered. */
    public void voidAuthorization(String authorizationId, String requestId) {
        http.requireConfigured();
        try {
            http.post("/v2/payments/authorizations/" + authorizationId + "/void", Map.of(), PaymentResponse.class,
                    requestId);
        } catch (PayPalRejectedException e) {
            if (!"AUTHORIZATION_ALREADY_VOIDED".equals(e.getIssue())) {
                throw e;
            }
        }
    }

    /** Refunds a capture in full. Repeating with the same {@code requestId} returns the first result. */
    public Payment refundCapture(String captureId, String requestId) {
        http.requireConfigured();
        return http.post("/v2/payments/captures/" + captureId + "/refund", Map.of(), PaymentResponse.class, requestId)
                .toPayment();
    }

    private static OrderState toState(OrderResponse order) {
        Capture capture = order.firstCapture();
        PayPalAmount amount = capture == null || capture.amount() == null ? null
                : new PayPalAmount(capture.amount().currencyCode(), new BigDecimal(capture.amount().value()));
        return new OrderState(order.id(), order.status(), capture == null ? null : capture.id(),
                capture == null ? null : capture.status(), amount);
    }

    public enum Intent {
        /** Money is moved right after approval ({@link #capture}). */
        CAPTURE,
        /** Money is reserved after approval and moved or released later. */
        AUTHORIZE
    }

    public record CheckoutOrder(String orderId, String approveUrl) {
    }

    /**
     * Authorization, capture or refund.
     *
     * @param status e.g. {@code CREATED} (authorization), {@code COMPLETED}, {@code PENDING}, {@code DECLINED}
     */
    public record Payment(String id, String status, @Nullable PayPalAmount amount) {
        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }

        public boolean isPending() {
            return "PENDING".equals(status);
        }
    }

    record PaymentResponse(String id, String status, @Nullable OrderAmount amount) {
        Payment toPayment() {
            return new Payment(id, status, amount == null ? null
                    : new PayPalAmount(amount.currencyCode(), new BigDecimal(amount.value())));
        }
    }

    /**
     * @param status        order status, e.g. {@code CREATED}, {@code APPROVED}, {@code COMPLETED}
     * @param captureId     set once a capture exists
     * @param captureStatus {@code COMPLETED} means the money arrived; {@code PENDING} may complete later;
     *                      {@code DECLINED}, {@code FAILED} mean no money
     * @param capturedAmount amount of the capture
     */
    public record OrderState(String orderId, String status, @Nullable String captureId, @Nullable String captureStatus,
                             @Nullable PayPalAmount capturedAmount) {
        public boolean isCaptureCompleted() {
            return "COMPLETED".equals(captureStatus);
        }

        public boolean isCapturePending() {
            return "PENDING".equals(captureStatus);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OrderAmount(@JsonProperty("currency_code") String currencyCode, String value) {
    }

    record OrderResponse(String id, String status, @Nullable List<PayPalHttp.Link> links,
                         @JsonProperty("purchase_units") @Nullable List<PurchaseUnit> purchaseUnits) {
        @Nullable Capture firstCapture() {
            if (purchaseUnits == null) return null;
            return purchaseUnits.stream()
                    .filter(unit -> unit.payments() != null && unit.payments().captures() != null)
                    .flatMap(unit -> unit.payments().captures().stream())
                    .findFirst().orElse(null);
        }

        @Nullable PaymentResponse firstAuthorization() {
            if (purchaseUnits == null) return null;
            return purchaseUnits.stream()
                    .filter(unit -> unit.payments() != null && unit.payments().authorizations() != null)
                    .flatMap(unit -> unit.payments().authorizations().stream())
                    .findFirst().orElse(null);
        }
    }

    record PurchaseUnit(@Nullable Payments payments) {
    }

    record Payments(@Nullable List<Capture> captures, @Nullable List<PaymentResponse> authorizations) {
    }

    record Capture(String id, String status, @Nullable OrderAmount amount) {
    }
}
