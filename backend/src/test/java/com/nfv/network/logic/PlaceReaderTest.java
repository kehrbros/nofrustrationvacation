package com.nfv.network.logic;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** See Docs/network.md#orte. */
class PlaceReaderTest {
    @Test
    void readsNamedCitiesTownsAndVillagesFromNodes() {
        List<Place> places = PlaceReader.read(Path.of("src/test/resources/osm/places.osm"));

        assertThat(places).containsExactly(
                new Place("Bigtown", PlaceKind.CITY, new GeoPoint(-41.29, 174.78)),
                new Place("Midtown", PlaceKind.TOWN, new GeoPoint(-41.30, 174.79)),
                new Place("Smallville", PlaceKind.VILLAGE, new GeoPoint(-41.31, 174.80)));
    }

    @Test
    void missingFileFails() {
        assertThatThrownBy(() -> PlaceReader.read(Path.of("src/test/resources/osm/none.osm")))
                .isInstanceOf(RuntimeException.class);
    }
}
