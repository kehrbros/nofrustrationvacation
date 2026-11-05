package com.nfv.incidents.logic;

import java.util.List;

/**
 * Where a reported incident is, in WGS84, as the source gives it. {@code incidents} turns it into road segments via
 * {@code network.SegmentMatching}; the reporting module ({@code input}) does not know {@code network}.
 */
public sealed interface IncidentLocation {
    record Coordinate(double lat, double lon) {
    }

    /** A spot, e.g. roadworks or a closure given as one point. */
    record Point(Coordinate coordinate) implements IncidentLocation {
    }

    /** One or more lines (e.g. a {@code MULTILINESTRING}), each in order. */
    record Lines(List<List<Coordinate>> lines) implements IncidentLocation {
        public Lines {
            lines = lines.stream().map(List::copyOf).toList();
        }
    }

    /** An area, not supported yet ({@code AREA_NOT_SUPPORTED}). */
    record Area(List<Coordinate> outline) implements IncidentLocation {
        public Area {
            outline = List.copyOf(outline);
        }
    }
}
