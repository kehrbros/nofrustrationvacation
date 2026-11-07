package com.nfv.network.logic;

import com.nfv.network.config.NetworkProperties;
import com.nfv.network.data.ExtractVersionRepository;
import com.nfv.network.entities.ExtractStatus;
import com.nfv.network.entities.ExtractVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
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
    private final PlaceWriter placeWriter;
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
                    // segments written before V37 have no link flag: only an import fills it
                    if (segmentWriter.hasLinks(region)) {
                        graphs.switchTo(region, loaded(current.get().getId(), NetworkGraphHopper.load(dir, config)));
                        log.info("Region {}: graph of version {} loaded", region, current.get().getId());
                        fillMissingPlaces(region, config, current.get().getId());
                        return status(region);
                    }
                    log.info("Region {}: segments without link flag, importing again", region);
                } catch (RuntimeException e) {
                    // e.g. graph written with other profiles
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
            placeWriter.replace(region, version.getId(), PlaceReader.read(osmFile));
            version.ready(hopper.dataDate(), segments, OffsetDateTime.now(clock));
            versionRepository.save(version);
            Optional<Long> previous = graphs.switchTo(region, loaded(version.getId(), hopper));
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

    /** Graph imported before V45: only the places are missing, the graph in use stays whatever happens here. */
    private void fillMissingPlaces(String region, NetworkProperties.Region config, long versionId) {
        if (placeWriter.hasPlaces(region)) {
            return;
        }
        try {
            List<Place> places = PlaceReader.read(extractSource.fetch(region, config));
            placeWriter.replace(region, versionId, places);
            log.info("Region {}: {} places added to version {}", region, places.size(), versionId);
        } catch (RuntimeException e) {
            log.warn("Region {}: places could not be read, none until the next import or start", region, e);
        }
    }

    private Graphs.LoadedGraph loaded(long versionId, NetworkGraphHopper hopper) {
        // Two directions per edge, edge key = 2 * edge + direction
        long[] mapping = segmentWriter.mapping(versionId, 2 * hopper.getBaseGraph().getEdges());
        return new Graphs.LoadedGraph(versionId, hopper, mapping, hopper.getBaseGraph().getBounds());
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