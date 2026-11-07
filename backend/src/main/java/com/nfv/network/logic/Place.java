package com.nfv.network.logic;

/** A place of an OSM extract, as stored by {@link PlaceWriter}. */
record Place(String name, PlaceKind kind, GeoPoint point) {
}
