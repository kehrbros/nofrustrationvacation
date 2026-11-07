package com.nfv.network.config;

import jakarta.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

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
     * @param zone       local time of the region, e.g. for an evening limit on a day's drive
     */
    public record Region(@Nullable Path osmFile, Path graphDir, String dataAccess, boolean ferries,
                         ZoneId zone) {
        public Region {
            Objects.requireNonNull(zone, "nfv.network.regions.*.zone is required");
        }
    }
}