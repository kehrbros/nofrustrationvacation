package com.nfv.input.logic;

/** The model gave no answer now, worth trying again later: quota used up or model not reachable. */
public class EnrichmentDeferredException extends RuntimeException {
    private final boolean quotaUsedUp;

    public EnrichmentDeferredException(String message) {
        this(message, false);
    }

    public EnrichmentDeferredException(String message, boolean quotaUsedUp) {
        super(message);
        this.quotaUsedUp = quotaUsedUp;
    }

    /** Nothing was wrong with the event or the model, it does not count as a failed try. */
    public boolean isQuotaUsedUp() {
        return quotaUsedUp;
    }
}
