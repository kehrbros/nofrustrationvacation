package com.vacantagent.paypal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vacantagent.paypal.client.PayPalHttp;
import com.vacantagent.paypal.config.PayPalProperties;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Payments of payers to the platform with the Orders API v2 (intent {@code CAPTURE}): create an order, the payer
 * approves it at PayPal, then capture it, see Docs/paypal.md.
 */
@Component
@RequiredArgsConstructor
public class PayPalCheckout {
    private final PayPalHttp http;
    private final PayPalProperties properties;

    /**
     * @param reference our id of the payment, stored as {@code custom_id} and used for idempotency: repeating the
     *                  call with the same reference returns the same order
     * @return the order, the payer has to be sent to {@link CheckoutOrder#approveUrl()}
     */
    public CheckoutOrder createOrder(String reference, PayPalAmount amount, String description) {
        http.requireConfigured();
        var body = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of(
                        "reference_id", reference,
                        "custom_id", reference,
                        "description", description,
                        "amount", new OrderAmount(amount.currency(), amount.formattedValue()))),
                "payment_source", Map.of("paypal", Map.of("experience_context", Map.of(
                        "brand_name", properties.brandName(),
                        "user_action", "PAY_NOW",
                        "shipping_preference", "NO_SHIPPING",
                        "return_url", properties.checkoutReturnUrl().toString(),
                        "cancel_url", properties.checkoutCancelUrl().toString()))));
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

    private static OrderState toState(OrderResponse order) {
        Capture capture = order.firstCapture();
        PayPalAmount amount = capture == null || capture.amount() == null ? null
                : new PayPalAmount(capture.amount().currencyCode(), new BigDecimal(capture.amount().value()));
        return new OrderState(order.id(), order.status(), capture == null ? null : capture.id(),
                capture == null ? null : capture.status(), amount);
    }

    public record CheckoutOrder(String orderId, String approveUrl) {
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
    }

    record PurchaseUnit(@Nullable Payments payments) {
    }

    record Payments(@Nullable List<Capture> captures) {
    }

    record Capture(String id, String status, @Nullable OrderAmount amount) {
    }
}
