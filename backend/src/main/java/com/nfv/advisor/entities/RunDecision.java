package com.nfv.advisor.entities;

/** What the user did with the proposals of a run, see Docs/advisor.md#choosing-and-dismissing. */
public enum RunDecision {
    /** Took one proposal, see {@code chosenPosition}. */
    ACCEPTED,
    /** None fits, plans again by hand. */
    DISMISSED
}
