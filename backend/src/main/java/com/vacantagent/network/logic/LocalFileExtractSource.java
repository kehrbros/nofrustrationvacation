package com.vacantagent.network.logic;

import com.vacantagent.network.config.NetworkProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Extract from the configured file ({@code osm-file}), the Geofabrik download follows later. */
@Component
class LocalFileExtractSource implements ExtractSource {
    @Override
    public Path fetch(String region, NetworkProperties.Region config) {
        Path file = config.osmFile();
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalStateException("region " + region + ": OSM file " + file + " not found");
        }
        return file;
    }
}