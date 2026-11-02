package com.nfv.trips.logic;

/**
 * Published within the transaction after the sections of a route were replaced, so that other modules (incidents)
 * can check the route again without the trips module depending on them.
 */
public record RouteChanged(long routeId) {
}
