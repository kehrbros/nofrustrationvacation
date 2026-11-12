package com.nfv.trips.logic;

/** Coordinates of a stop or waypoint lie outside all regions of the road network, see Docs/domain.md#trips. */
public class LocationOutsideRegionsException extends InvalidTripInputException {
    public LocationOutsideRegionsException() {
        super("latitude", "location outside the supported regions");
    }
}
