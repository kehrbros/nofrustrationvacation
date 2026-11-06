package com.nfv.advisor.entities;

/** What a proposal asks the user to do. */
public enum ProposalKind {
    /** Another way, chosen with its points. */
    REROUTE,
    /** The planned way with another departure, chosen with {@code departureAt}. */
    SHIFT_DEPARTURE,
    /** Accept the delay, nothing to choose. */
    KEEP_ROUTE
}
