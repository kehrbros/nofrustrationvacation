package com.nfv.incidents.logic;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Published within the transaction of an assessment if a consequence of the route became {@code TOO_LATE}, or is new
 * with it, and its passage is not over. A replanning replaces the consequences, so they all count as new then:
 * whether the situation was handled before is for the listener to find out, see
 * Docs/advisor.md#automatisch-nur-too_late.
 *
 * @param incidentIds all incidents with {@code TOO_LATE} on the route, also those already passed: time going by
 *                    alone does not make it a new situation
 * @param deadline    arrival at the target stop
 */
public record RouteTooLate(long routeId, Set<Long> incidentIds, OffsetDateTime deadline) {
}
