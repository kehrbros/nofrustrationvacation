package com.nfv.advisor.logic;

import com.nfv.incidents.logic.ActiveIncidents;
import com.nfv.incidents.logic.RouteConsequence;
import com.nfv.trips.logic.RouteSchedule;
import com.nfv.trips.logic.RouteSchedules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** Hash of what proposals depend on, to see whether stored ones are out of date, see Docs/advisor.md#veraltete-vorschläge. */
@Component
@RequiredArgsConstructor
class RouteSituations {
    private final ActiveIncidents activeIncidents;
    private final RouteSchedules routeSchedules;

    /** Incidents that count with their category, deadline and planned arrival, compared as instants. */
    String of(long routeId) {
        List<RouteConsequence> counting = activeIncidents.consequencesOnRoute(routeId).stream()
                .filter(RouteConsequence::counts).sorted(Comparator.comparingLong(RouteConsequence::incidentId)).toList();
        RouteSchedule schedule = routeSchedules.of(routeId).orElse(null);
        String text = counting.stream().map(c -> c.incidentId() + ":" + c.category()).collect(Collectors.joining(","))
                + "|" + (schedule == null || schedule.deadline() == null ? "" : schedule.deadline().toInstant())
                + "|" + (schedule == null || schedule.plannedArrival() == null ? "" : schedule.plannedArrival().toInstant());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
