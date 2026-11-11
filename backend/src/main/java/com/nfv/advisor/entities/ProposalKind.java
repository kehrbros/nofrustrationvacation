package com.nfv.advisor.entities;

/** What a proposal asks the user to do. */
public enum ProposalKind {
    /** Another way, chosen with its points. */
    REROUTE,
    /** The planned way with another departure, chosen with {@code departureAt}. */
    SHIFT_DEPARTURE,
    /** Accept the delay, nothing to choose. */
    KEEP_ROUTE,
    /** A night at a hotel along the way, before what blocks it, see {@code stay}. The route option it lies on, if any. */
    STOPOVER,
    /** Another accommodation at the target stop, e.g. one that is still reachable for the late arrival, see {@code stay}. */
    TARGET_STAY,
    /** Several steps, e.g. a night on the way, the drive on and the accommodation at the target, see {@code steps}. */
    PLAN
}
