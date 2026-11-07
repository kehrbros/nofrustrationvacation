package com.nfv.trips.logic;

import jakarta.annotation.Nullable;

/**
 * Whether a new accommodation of a stop lies in another place, so the stop and its routes follow it, see
 * Docs/accommodation-alternatives.md#route-nach-dem-umbuchen. Routes run from stop to stop; within the same place the
 * accommodation does not change them.
 */
public final class StayPlaces {
    /** Farther than this from the stop the accommodation counts as another place. */
    public static final int OTHER_PLACE_METERS = 10_000;

    private StayPlaces() {
    }

    /** @return true if the stop has no coordinates yet or the point is more than {@link #OTHER_PLACE_METERS} away */
    public static boolean isOtherPlace(@Nullable Double stopLatitude, @Nullable Double stopLongitude,
                                       double latitude, double longitude) {
        return stopLatitude == null || stopLongitude == null
                || distanceMeters(stopLatitude, stopLongitude, latitude, longitude) > OTHER_PLACE_METERS;
    }

    /** Great-circle distance. */
    public static int distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return (int) Math.round(6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}
