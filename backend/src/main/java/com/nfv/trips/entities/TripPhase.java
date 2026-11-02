package com.nfv.trips.entities;

/** Position of a {@link Trip} relative to a point in time, drives the trip list (current on top, tabs upcoming/past). */
public enum TripPhase {
    UPCOMING,
    CURRENT,
    PAST
}
