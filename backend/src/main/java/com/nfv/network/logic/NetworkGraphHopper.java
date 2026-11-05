package com.nfv.network.logic;

import com.carrotsearch.hppc.IntDoubleHashMap;
import com.carrotsearch.hppc.IntDoubleMap;
import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.routing.DefaultWeightingFactory;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.config.LMProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.json.Statement;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.TurnCostsConfig;
import com.nfv.network.config.NetworkProperties;
import jakarta.annotation.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Embedded GraphHopper of one region and version, profile {@code car} with turn costs and landmarks. */
final class NetworkGraphHopper extends GraphHopper {
    static final String PROFILE = "car";
    /** Request hint: {@code IntSet} of directed edge keys that are impassable. */
    static final String BLOCKED_EDGE_KEYS = "blocked_edge_keys";
    /** Request hint: {@code IntDoubleMap} of directed edge keys to extra seconds. */
    static final String DELAYED_EDGE_KEYS = "delayed_edge_keys";
    /** Changing these needs a new import of every region. */
    private static final String ENCODED_VALUES = "car_access|block_private=false, car_average_speed, road_access, "
            + "road_class, road_class_link, road_environment, osm_way_id, max_speed, surface";
    /**
     * Not drivable by car, skipped during the import: roads are then only split at junctions with other roads, which
     * keeps segments stable. Not {@code pedestrian}, some pedestrian zones allow cars.
     */
    private static final String IGNORED_HIGHWAYS = "footway,cycleway,path,steps,bridleway,construction";

    static NetworkGraphHopper importGraph(Path osmFile, Path graphDir, NetworkProperties.Region region) {
        delete(graphDir);
        return open(osmFile, graphDir, region);
    }

    static NetworkGraphHopper load(Path graphDir, NetworkProperties.Region region) {
        return open(null, graphDir, region);
    }

    private static NetworkGraphHopper open(@Nullable Path osmFile, Path graphDir, NetworkProperties.Region region) {
        GraphHopperConfig config = new GraphHopperConfig();
        if (osmFile != null) {
            config.putObject("datareader.file", osmFile.toString());
        }
        config.putObject("graph.location", graphDir.toString());
        config.putObject("graph.dataaccess.default_type", region.dataAccess());
        config.putObject("graph.encoded_values", ENCODED_VALUES);
        config.putObject("import.osm.ignored_highways", IGNORED_HIGHWAYS);
        CustomModel model = GHUtility.loadCustomModelFromJar("car.json");
        if (!region.ferries()) {
            model.addToPriority(Statement.If("road_environment == FERRY", Statement.Op.MULTIPLY, "0"));
        }
        config.setProfiles(List.of(new Profile(PROFILE).setCustomModel(model)
                .setTurnCostsConfig(TurnCostsConfig.car())));
        config.setLMProfiles(List.of(new LMProfile(PROFILE)));
        NetworkGraphHopper hopper = new NetworkGraphHopper();
        hopper.init(config);
        hopper.importOrLoad();
        return hopper;
    }

    @Override
    protected WeightingFactory createWeightingFactory() {
        WeightingFactory defaults = new DefaultWeightingFactory(getBaseGraph(), getEncodingManager());
        return (profile, hints, disableTurnCosts) -> {
            Weighting weighting = defaults.createWeighting(profile, hints, disableTurnCosts);
            IntSet blocked = hints.getObject(BLOCKED_EDGE_KEYS, new IntHashSet());
            IntDoubleMap delayed = hints.getObject(DELAYED_EDGE_KEYS, new IntDoubleHashMap());
            return blocked.isEmpty() && delayed.isEmpty() ? weighting
                    : new ScenarioWeighting(weighting, blocked, delayed);
        };
    }

    /** Timestamp of the OSM data from the file header, if the file has one. */
    @Nullable
    OffsetDateTime dataDate() {
        String date = getProperties().get("datareader.data.date");
        return date == null || date.isEmpty() ? null : OffsetDateTime.ofInstant(Instant.parse(date), ZoneOffset.UTC);
    }

    static void delete(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}