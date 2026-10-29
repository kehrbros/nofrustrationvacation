package com.vacantagent.accounting.entities;

/** Reason of a {@link LedgerEntry}, the entry's {@code referenceId} points to the matching record. */
public enum EntryType {
    /** Credit topped up via PayPal, reference: {@link TopUp}. */
    TOP_UP,
    /** Earnings paid out via PayPal, reference: {@link Payout}. */
    PAYOUT,
    /** Payout failed or returned, the amount is back on the earnings account, reference: {@link Payout}. */
    PAYOUT_REVERSAL,
    /** Consumer paid for LLM usage, reference: agreement of the removed market. Kept for existing entries. */
    USAGE_CHARGE,
    /** Provider earned from LLM usage, reference: agreement of the removed market. Kept for existing entries. */
    USAGE_EARNING,
    /** Balance taken over from {@code app_user.account_balance} by the migration, no reference. */
    OPENING_BALANCE
}
