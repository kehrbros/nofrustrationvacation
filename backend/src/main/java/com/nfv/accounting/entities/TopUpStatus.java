package com.nfv.accounting.entities;

public enum TopUpStatus {
    /** PayPal order created, waiting for the payer's approval. */
    CREATED,
    /** Captured, but PayPal holds the money (e.g. review). Credited once PayPal completes it. */
    PENDING,
    /** Money arrived, credit booked. Final. */
    COMPLETED,
    /** Payer cancelled at PayPal. Final. */
    CANCELLED,
    /** Payment declined or creating the order failed. Final. */
    FAILED
}
