package com.nfv.recommendations.logic;

import com.nfv.recommendations.data.SuggestionSetRepository;
import com.nfv.recommendations.entities.SuggestionSet;
import com.nfv.recommendations.entities.SuggestionStatus;
import com.nfv.trips.logic.RouteChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Route planned again or its sections discarded: its current sets no longer fit and become {@code STALE}, in the
 * transaction of the change. Not computed again: planning computes on the next request.
 */
@Component("recommendationsRouteChangedListener")
@RequiredArgsConstructor
class RouteChangedListener {
    private final SuggestionSetRepository setRepository;

    @EventListener
    public void onRouteChanged(RouteChanged event) {
        setRepository.findByRouteIdAndStatus(event.routeId(), SuggestionStatus.CURRENT).forEach(SuggestionSet::stale);
    }
}