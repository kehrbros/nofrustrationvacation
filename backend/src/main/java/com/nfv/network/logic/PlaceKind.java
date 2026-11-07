package com.nfv.network.logic;

import jakarta.annotation.Nullable;

/** Size of a place, from the OSM tag {@code place}. Smaller places are not imported. */
public enum PlaceKind {
    CITY, TOWN, VILLAGE;

    /** @return {@code null} for every other value of the tag */
    static @Nullable PlaceKind ofTag(@Nullable String tag) {
        return switch (tag) {
            case "city" -> CITY;
            case "town" -> TOWN;
            case "village" -> VILLAGE;
            case null, default -> null;
        };
    }
}
