package com.nfv.network.logic;

import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.osm.OSMInputFile;
import com.graphhopper.reader.osm.SkipOptions;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the places of an OSM extract in a pass of its own, GraphHopper keeps no nodes of its import, see
 * Docs/network.md#orte. Only nodes: a place mapped only as a way or relation is not found.
 */
final class PlaceReader {
    private PlaceReader() {
    }

    /** Named nodes with {@code place=city|town|village}. */
    static List<Place> read(Path osmFile) {
        List<Place> places = new ArrayList<>();
        try (OSMInputFile input = new OSMInputFile(osmFile.toFile())) {
            input.setWorkerThreads(2).setSkipOptions(new SkipOptions(false, true, true)).open();
            ReaderElement element;
            while ((element = input.getNext()) != null) {
                if (element instanceof ReaderNode node) {
                    PlaceKind kind = PlaceKind.ofTag(node.getTag("place"));
                    String name = node.getTag("name");
                    if (kind != null && name != null && !name.isBlank()) {
                        places.add(new Place(name.strip(), kind, new GeoPoint(node.getLat(), node.getLon())));
                    }
                }
            }
        } catch (IOException | XMLStreamException e) {
            throw new IllegalStateException("cannot read places from " + osmFile, e);
        }
        return places;
    }
}
