package com.nfv.advisor.entities;

/** Where taking a plan stands, see Docs/incident-plans.md#one-click-execution. */
public enum PlanExecutionStatus {
    /** Hotels prebooked, the user approves at PayPal. */
    AWAITING_PAYMENT,
    /** Hotels booked, the trip is being changed. */
    APPLYING,
    /** The trip is changed and the hotels are paid. */
    APPLIED,
    /** Nothing was paid, the trip is as it was. */
    FAILED,
    /** Hotels booked and paid, but the trip could not be changed: the user plans by hand. */
    APPLIED_PARTLY
}
