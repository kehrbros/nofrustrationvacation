package com.vacantagent.network.logic;

import com.vacantagent.network.config.NetworkProperties;
import com.vacantagent.network.data.ExtractVersionRepository;
import com.vacantagent.network.entities.ExtractStatus;
import com.vacantagent.network.entities.ExtractVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Imports the OSM extract of a region and switches to the new graph, see Docs/network.md#import-und-aktualisierung. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionImport {
    private final NetworkProperties properties;
    private final ExtractSource extractSource;
    private final ExtractVersionRepository versionRepository;
    private final SegmentWriter segmentWriter;
    private final Graphs graphs;
    private final Clock clock;

    /** Loads the graph of the current version if it is on disk, otherwise imports the region. */
    public RegionStatus loadOrImport(String region) {
        NetworkProperties.Region config = config(region);
        Optional<ExtractVersion> current = versionRepository
                .findFirstByRegionAndStatusOrderByIdDesc(region, ExtractStatus.READY);
        if (current.isPresent()) {
            Path dir = graphDir(region, current.get().getId());
            if (Files.isDirectory(dir)) {
                try {
                    graphs.switchTo(region, current.get().getId(), NetworkGraphHopper.load(dir, config));
                    log.info("Region {}: graph of version {} loaded", region, current.get().getId());
                    return status(region);
                } catch (RuntimeException e) {
                    // e.g. graph written with other encoded values or profiles
                    log.warn("Region {}: graph of version {} not usable, importing again", region,
                            current.get().getId(), e);
                }
            }
        }
        return importRegion(region);
    }

    /** Imports the region into a new version, the previous graph stays in use if anything fails. */
    public RegionStatus importRegion(String region) {
        NetworkProperties.Region config = config(region);
        ExtractVersion version = versionRepository.save(new ExtractVersion(region, OffsetDateTime.now(clock)));
        graphs.importing(region);
        Path dir = graphDir(region, version.getId());
        NetworkGraphHopper hopper = null;
        try {
            Path osmFile = extractSource.fetch(region, config);
            hopper = NetworkGraphHopper.importGraph(osmFile, dir, config);
            int segments = segmentWriter.write(region, version.getId(), hopper);
            version.ready(hopper.dataDate(), segments, OffsetDateTime.now(clock));
            versionRepository.save(version);
            Optional<Long> previous = graphs.switchTo(region, version.getId(), hopper);
            previous.ifPresent(old -> {
                segmentWriter.deleteMapping(old);
                NetworkGraphHopper.delete(graphDir(region, old));
            });
            log.info("Region {}: version {} ready, {} segments", region, version.getId(), segments);
        } catch (RuntimeException e) {
            log.error("Region {}: import of version {} failed", region, version.getId(), e);
            if (hopper != null) {
                hopper.close();
            }
            NetworkGraphHopper.delete(dir);
            version.failed(OffsetDateTime.now(clock));
            versionRepository.save(version);
            graphs.failed(region);
        }
        return status(region);
    }

    public RegionStatus status(String region) {
        return graphs.status(region);
    }

    private NetworkProperties.Region config(String region) {
        NetworkProperties.Region config = properties.regions().get(region);
        if (config == null) {
            throw new IllegalArgumentException("unknown region " + region);
        }
        return config;
    }

    private Path graphDir(String region, long versionId) {
        return config(region).graphDir().resolve(Long.toString(versionId));
    }
}