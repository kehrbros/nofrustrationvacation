package com.nfv.network.config;

import jakarta.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Map;

/** Regions of the road network, see Docs/network.md#konfiguration. */
@ConfigurationProperties("nfv.network")
public record NetworkProperties(boolean importOnStart, Map<String, Region> regions) {
    public NetworkProperties {
        regions = regions == null ? Map.of() : regions;
    }

    /**
     * @param osmFile    extract on disk (download follows later)
     * @param graphDir   GraphHopper graphs of the region, one subdirectory per {@code ExtractVersion}
     * @param dataAccess {@code MMAP} (graph outside the heap) or {@code RAM_STORE} (tests)
     * @param ferries    false: ferries are not used for routing (part of the graph, change needs a new import)
     */
    public record Region(@Nullable Path osmFile, Path graphDir, String dataAccess, DrivingSide drivingSide,
                         boolean ferries) {
    }

    public enum DrivingSide {
        LEFT, RIGHT
    }
}