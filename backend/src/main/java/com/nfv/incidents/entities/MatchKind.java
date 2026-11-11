package com.nfv.incidents.entities;

/** How the passage of a route hits an incident, see Docs/consequence-assessment.md#passage-tolerance-decided-2026-11-04. */
public enum MatchKind {
    /** The planned passage falls into the incident. */
    DIRECT,
    /** Only the passage moved by earlier delays falls into the incident. */
    BY_SHIFT,
    /** Only the passage widened by the tolerance falls into it; never {@link Category#TOO_LATE}. */
    MARGINAL
}
