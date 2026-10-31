package com.vacantagent.network.logic;

import java.util.Set;

/** @param blockedSegmentIds segments the route must not use, e.g. closures */
public record RouteOptions(Set<Long> blockedSegmentIds) {
    public static RouteOptions none() {
        return new RouteOptions(Set.of());
    }
}