package com.nfv.recommendations.logic;

import com.nfv.network.logic.RoutePoint;
import com.nfv.trips.logic.PlannedSection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Hash over everything a set is computed from: points, waypoint mode, departure, restrictions, planned sections and
 * count. Equal hash → the stored set is reused instead of routing again.
 */
final class BasisHash {
    private BasisHash() {
    }

    static String of(Scenario scenario) {
        StringBuilder basis = new StringBuilder();
        basis.append(scenario.routeId()).append('|').append(scenario.trigger()).append('|')
                .append(scenario.waypoints()).append('|').append(scenario.count()).append('|')
                .append(scenario.departure().toInstant()).append('|');
        point(basis, scenario.start());
        scenario.vias().forEach(via -> {
            basis.append(via.waypointId()).append(':');
            point(basis, via.point());
        });
        point(basis, scenario.end());
        basis.append(new TreeSet<>(scenario.constraints().blockedSegmentIds())).append('|');
        new TreeMap<>(scenario.constraints().delayedSegments()).forEach((id, delay) ->
                basis.append(id).append('=').append(delay).append(','));
        basis.append('|');
        for (PlannedSection section : scenario.reference()) {
            basis.append(String.format(Locale.ROOT, "%d:%.6f:%.6f:%s:%s;", section.segmentId(),
                    section.fromFraction(), section.toFraction(), section.expectedPassageStart().toInstant(),
                    section.expectedPassageEnd().toInstant()));
        }
        return sha256(basis.toString());
    }

    private static void point(StringBuilder basis, RoutePoint point) {
        basis.append(String.format(Locale.ROOT, "%.7f,%.7f,%s,%s|", point.point().lat(), point.point().lon(),
                point.kind(), point.roadHint()));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}