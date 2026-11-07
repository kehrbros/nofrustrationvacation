package com.nfv.advisor.entities;

/** What the user did with the proposals of a run, see Docs/advisor.md#auswählen-und-verwerfen. */
public enum RunDecision {
    /** Took one proposal, see {@code chosenPosition}. */
    ACCEPTED,
    /** None fits, plans again by hand. */
    DISMISSED
}
