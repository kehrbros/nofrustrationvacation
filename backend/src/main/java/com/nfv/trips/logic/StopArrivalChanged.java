package com.nfv.trips.logic;

import java.util.Set;

/**
 * Published within the transaction when the arrival of a stop changed. The arrival does not affect planning, so
 * {@link RouteChanged} is not published; the incidents module assesses the routes to the stop again.
 *
 * @param routeIds routes whose target stop it is
 */
public record StopArrivalChanged(long stopId, Set<Long> routeIds) {
}
