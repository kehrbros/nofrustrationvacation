package com.nfv.incidents.entities;

/** What a {@link Consequence} means for the arrival at the target stop, see Docs/consequence-assessment.md. */
public enum Category {
    /** Arrival after the deadline of the target stop, or not passable at all. */
    TOO_LATE,
    /** Noticeable delay, the deadline still holds (or there is none). */
    DELAYED,
    /** On the route at the time of passage, but without noticeable delay. */
    NO_IMPACT
}
