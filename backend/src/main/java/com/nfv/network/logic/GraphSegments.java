package com.nfv.network.logic;

import com.graphhopper.GraphHopper;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.OSMWayID;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;
import jakarta.annotation.Nullable;

import java.util.function.Consumer;

/**
 * Directed, drivable edges of a graph as segment rows. Key: OSM way, junction coordinates (1e-7 degrees) and a hash of
 * the geometry. Zero-length edges (barriers) are skipped, identical duplicate edges get the same key, see
 * Docs/network.md#datenmodell.
 */
final class GraphSegments {
    private static final int MAX_TEXT = 255;

    record Row(int edgeKey, long osmWayId, int fromLat, int fromLon, int toLat, int toLon, int geometryHash,
               @Nullable String ref, @Nullable String name, String roadClass, boolean bridge, boolean tunnel,
               double lengthMeters, String wkt) {
    }

    private GraphSegments() {
    }

    static void forEach(GraphHopper hopper, Consumer<Row> rows) {
        BaseGraph graph = hopper.getBaseGraph();
        BooleanEncodedValue access = hopper.getEncodingManager().getBooleanEncodedValue(VehicleAccess.key("car"));
        Attributes attributes = new Attributes(
                hopper.getEncodingManager().getIntEncodedValue(OSMWayID.KEY),
                hopper.getEncodingManager().getEnumEncodedValue(RoadClass.KEY, RoadClass.class),
                hopper.getEncodingManager().getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment.class));
        AllEdgesIterator edges = graph.getAllEdges();
        while (edges.next()) {
            if (edges.get(access)) {
                row(graph.getEdgeIteratorStateForKey(edges.getEdgeKey()), attributes, rows);
            }
            if (edges.getReverse(access)) {
                row(graph.getEdgeIteratorStateForKey(GHUtility.reverseEdgeKey(edges.getEdgeKey())), attributes, rows);
            }
        }
    }

    private record Attributes(IntEncodedValue wayId, EnumEncodedValue<RoadClass> roadClass,
                              EnumEncodedValue<RoadEnvironment> environment) {
    }

    private static void row(EdgeIteratorState edge, Attributes attributes, Consumer<Row> rows) {
        PointList points = edge.fetchWayGeometry(FetchMode.ALL);
        int last = points.size() - 1;
        int fromLat = e7(points.getLat(0));
        int fromLon = e7(points.getLon(0));
        int toLat = e7(points.getLat(last));
        int toLon = e7(points.getLon(last));
        if (fromLat == toLat && fromLon == toLon) {
            return;
        }
        int hash = 1;
        StringBuilder wkt = new StringBuilder("LINESTRING(");
        for (int i = 0; i < points.size(); i++) {
            hash = 31 * (31 * hash + e7(points.getLat(i))) + e7(points.getLon(i));
            if (i > 0) {
                wkt.append(',');
            }
            wkt.append(points.getLon(i)).append(' ').append(points.getLat(i));
        }
        wkt.append(')');
        RoadEnvironment environment = edge.get(attributes.environment());
        rows.accept(new Row(edge.getEdgeKey(), edge.get(attributes.wayId()), fromLat, fromLon, toLat, toLon, hash,
                text(edge.getValue(Parameters.Details.STREET_REF)), text(edge.getName()),
                edge.get(attributes.roadClass()).name(), environment == RoadEnvironment.BRIDGE,
                environment == RoadEnvironment.TUNNEL, edge.getDistance(), wkt.toString()));
    }

    @Nullable
    private static String text(@Nullable Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String text = value.toString();
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    private static int e7(double degrees) {
        return (int) Math.round(degrees * 1e7);
    }
}