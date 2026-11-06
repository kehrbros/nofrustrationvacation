package com.nfv.incidents.logic;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Published within the transaction of an assessment if the route has a {@code TOO_LATE} consequence whose passage is
 * not over. Says nothing about whether this is new, see Docs/advisor.md#automatisch-nur-too_late.
 *
 * @param incidentIds all incidents with {@code TOO_LATE} on the route, also those already passed: time going by
 *                    alone does not make it a new situation
 * @param deadline    arrival at the target stop
 */
public record RouteTooLate(long routeId, Set<Long> incidentIds, OffsetDateTime deadline) {
}
