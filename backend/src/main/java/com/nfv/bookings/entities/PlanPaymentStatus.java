package com.nfv.bookings.entities;

/** Steps of paying a plan, see Docs/incident-plans.md#one-click-execution. */
public enum PlanPaymentStatus {
    /** Every hotel prebooked, no order yet. */
    PREBOOKED,
    /** PayPal order created, waiting for the approval of the user. */
    PAYMENT_PENDING,
    /** Money reserved and every hotel booked at LiteAPI; the plan changes the trip next. */
    SECURED,
    /** Captured, bookings created (a hotel whose capture failed needs attention). */
    SETTLED,
    /** Nothing booked, nothing paid. */
    FAILED
}
