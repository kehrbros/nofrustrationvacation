package com.nfv.incidents.logic;

import com.nfv.trips.logic.RouteChanged;
import com.nfv.trips.logic.StopArrivalChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Assesses routes again after changes in trips, in the transaction of the change. */
@Component
@RequiredArgsConstructor
class RouteChangedListener {
    private final ConsequenceMatcher consequenceMatcher;

    /** New or changed route: checked against the active incidents on its segments. */
    @EventListener
    public void onRouteChanged(RouteChanged event) {
        consequenceMatcher.matchRoute(event.routeId());
    }

    /** New deadline of the routes to the stop: categories may change ({@code DELAYED} ↔ {@code TOO_LATE}). */
    @EventListener
    public void onStopArrivalChanged(StopArrivalChanged event) {
        consequenceMatcher.assessRoutes(event.routeIds());
    }
}
