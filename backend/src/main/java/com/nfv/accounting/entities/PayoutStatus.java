package com.nfv.accounting.entities;

public enum PayoutStatus {
    /** Debited from the earnings account, not yet sent to PayPal. */
    REQUESTED,
    /** Sent to PayPal, waiting for the final state. */
    SUBMITTED,
    /** Money arrived at the receiver. Final. */
    COMPLETED,
    /** Refused or returned by PayPal, the amount is back on the earnings account. Final. */
    FAILED,
    /** Sending failed with an unknown outcome (timeout, 5xx). Needs a manual check in the PayPal dashboard. */
    UNKNOWN
}
