package com.nfv.incidents.logic;

import jakarta.annotation.Nullable;

/**
 * Result of {@link IncidentReporting#report}: the incident created or changed, or why there is none.
 *
 * @param incidentId {@code null} exactly if there is a {@code reason}
 */
public record ReportOutcome(@Nullable Long incidentId, @Nullable Reason reason) {
    public enum Reason {
        /** No road segment found at the location (e.g. outside the network of the region). */
        NO_SEGMENTS,
        /** Areas are not located yet. */
        AREA_NOT_SUPPORTED,
        /** The network of the region is not loaded yet: report again later. */
        ROUTING_UNAVAILABLE,
        /** Ended before an incident was created, nothing to do. */
        ENDED
    }

    public static ReportOutcome incident(long incidentId) {
        return new ReportOutcome(incidentId, null);
    }

    public static ReportOutcome none(Reason reason) {
        return new ReportOutcome(null, reason);
    }
}
