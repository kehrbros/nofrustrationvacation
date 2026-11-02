package com.nfv.network.logic;

import java.util.List;

/** Line in WGS84, in order. */
public record GeoLine(List<GeoPoint> points) {
}