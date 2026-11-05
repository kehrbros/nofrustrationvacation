package com.nfv.bookings.entities;

/** Steps of booking an alternative, see Docs/accommodation-alternatives.md#zahlung. */
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
    FAILED
}
