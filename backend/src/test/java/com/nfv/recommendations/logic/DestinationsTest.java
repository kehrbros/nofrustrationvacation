package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.PlaceKind;
import com.nfv.network.logic.Places;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Order of other targets, see Docs/incident-plans.md#rules. */
class DestinationsTest {
    private static final GeoPoint QUEENSTOWN = new GeoPoint(-45.0312, 168.6626);

    @Test
    void closeToTheTargetAndTheNextStopTownsBeforeVillages() {
        Places.PlaceAround town = new Places.PlaceAround("Cromwell", PlaceKind.TOWN, new GeoPoint(-45.0384, 169.1990),
                45_000);
        Places.PlaceAround village = new Places.PlaceAround("Luggate", PlaceKind.VILLAGE,
                new GeoPoint(-44.7380, 169.2800), 15_000);
        Places.PlaceAround away = new Places.PlaceAround("Omarama", PlaceKind.TOWN, new GeoPoint(-44.4870, 169.9700),
                60_000);

        assertThat(Destinations.score(village, null)).isEqualTo(35_000);
        assertThat(Destinations.score(town, QUEENSTOWN)).isLessThan(Destinations.score(away, QUEENSTOWN));
    }

    @Test
    void straightLineIsGoodEnough() {
        // Wānaka to Queenstown, about 52 km as the crow flies
        assertThat(Destinations.meters(new GeoPoint(-44.7032, 169.1321), QUEENSTOWN)).isCloseTo(52_000, within(3_000.0));
    }
}
