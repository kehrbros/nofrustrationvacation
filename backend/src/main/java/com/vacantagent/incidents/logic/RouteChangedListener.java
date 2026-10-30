package com.vacantagent.incidents.logic;

import com.vacantagent.trips.logic.RouteChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Checks a new or changed route against the active incidents on its segments, in the transaction of the change. */
@Component
@RequiredArgsConstructor
class RouteChangedListener {
    private final ConsequenceMatcher consequenceMatcher;

    @EventListener
    public void onRouteChanged(RouteChanged event) {
        consequenceMatcher.matchRoute(event.routeId());
    }
}
