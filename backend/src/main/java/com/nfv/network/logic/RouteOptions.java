package com.nfv.network.logic;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Restrictions in the network for one request, see Docs/network.md#routing-details.
 *
 * @param blockedSegmentIds segments the route must not use, e.g. closures; win over a delay on the same segment
 * @param delayedSegments   extra time per segment (≥ 0), e.g. road works. Only changes which way is chosen, the times
 *                          in {@link RouteResult} stay without the delay
 */
public record RouteOptions(Set<Long> blockedSegmentIds, Map<Long, Duration> delayedSegments) {
    public RouteOptions {
        blockedSegmentIds = Set.copyOf(blockedSegmentIds);
        delayedSegments = Map.copyOf(delayedSegments);
        if (delayedSegments.values().stream().anyMatch(Duration::isNegative)) {
            throw new IllegalArgumentException("delays must not be negative, landmarks need weights that only grow");
        }
    }

    public static RouteOptions none() {
        return new RouteOptions(Set.of(), Map.of());
    }

    public static RouteOptions blocked(Set<Long> blockedSegmentIds) {
        return new RouteOptions(blockedSegmentIds, Map.of());
    }
}