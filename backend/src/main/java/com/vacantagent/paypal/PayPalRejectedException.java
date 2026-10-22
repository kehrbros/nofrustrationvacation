package com.vacantagent.paypal;

import jakarta.annotation.Nullable;
import lombok.Getter;

/**
 * PayPal refused the request with a 4xx status. The request had no effect and may not be repeated unchanged.
 */
@Getter
public class PayPalRejectedException extends PayPalException {
    private final int status;
    /** PayPal error name or issue, e.g. {@code INSUFFICIENT_FUNDS}, {@code ORDER_NOT_APPROVED}. */
    private final @Nullable String issue;
    private final @Nullable String debugId;

    public PayPalRejectedException(int status, @Nullable String issue, @Nullable String debugId, String message) {
        super("PayPal rejected the request (" + status + ", " + issue + ", debug id " + debugId + "): " + message);
        this.status = status;
        this.issue = issue;
        this.debugId = debugId;
    }
}
