package com.nfv.trips.logic;

import com.nfv.common.errorhandling.BusinessException;

import java.util.Locale;

/** A stop, route or waypoint does not exist or is not part of the given trip of the user. */
public class TripItemNotFoundException extends BusinessException {
    private final String item;

    public TripItemNotFoundException(String item, long id) {
        super(item + " " + id + " not found");
        this.item = item;
    }

    /** Error code for the API, e.g. {@code STOP_NOT_FOUND}. */
    public String code() {
        return item.toUpperCase(Locale.ROOT) + "_NOT_FOUND";
    }
}