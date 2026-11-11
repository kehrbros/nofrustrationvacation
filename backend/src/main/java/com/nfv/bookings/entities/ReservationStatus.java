package com.nfv.bookings.entities;

/** Steps of booking an alternative, see Docs/accommodation-alternatives.md#payment. */
public enum ReservationStatus {
    /** Offer prebooked at LiteAPI, price locked. */
    PREBOOKED,
    /** PayPal order created, waiting for the user's approval. */
    PAYMENT_PENDING,
    /** Money reserved at PayPal. */
    AUTHORIZED,
    /** Booked at LiteAPI, money still to be captured. */
    PROVIDER_BOOKED,
    /** Money captured, booking created. */
    BOOKED,
    /** Nothing booked, no money moved. */
    FAILED,
    /**
     * Booked at LiteAPI, but the money could not be captured (or the booking state is unclear): the booking is kept,
     * someone has to look at it.
     */
    NEEDS_ATTENTION,
    /** Booked, then cancelled at LiteAPI by the user; refunded with PayPal once {@code payPalRefundId} is set. */
    CANCELLED
}
