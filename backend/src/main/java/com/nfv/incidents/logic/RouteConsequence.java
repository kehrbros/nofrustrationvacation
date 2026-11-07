package com.nfv.incidents.logic;

import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.MatchKind;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Consequence for other modules, without the entity, see {@link ActiveIncidents}.
 *
 * @param description     text of the source, written by third parties
 * @param delay           own delay for this route as assessed, {@code null}: not passable
 * @param delayAssumed    {@code delay} is the assumption of the source
 * @param from            start of the window hit, without windows of the incident
 * @param until           end of the window hit, without windows of the incident, {@code null}: until further notice
 * @param passageAt       passage of the place, moved by the delays before it
 * @param expectedArrival arrival at the target stop after this consequence, {@code null} if not passable
 * @param over            the route has passed the place, including the wait there
 */
public record RouteConsequence(long id, long routeId, long incidentId, @Nullable String description, Impact impact,
                               @Nullable Category category, MatchKind match, @Nullable Duration delay,
                               boolean delayAssumed, Set<Long> segmentIds, OffsetDateTime from,
                               @Nullable OffsetDateTime until, @Nullable OffsetDateTime passageAt,
                               @Nullable OffsetDateTime expectedArrival, boolean over) {

    /** Whether it matters for the arrival: noticeable, not only within the tolerance and not passed yet. */
    public boolean counts() {
        return !over && match != MatchKind.MARGINAL && (category == Category.TOO_LATE || category == Category.DELAYED);
    }
}
