package com.vacantagent.network.logic;

import com.graphhopper.util.shapes.BBox;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * The graph in use per region and the state of its import. Routes run under a read lock, switching to a new graph
 * takes the write lock, so a running route never uses a closed graph.
 */
@Component
class Graphs {
    /** @param segmentByEdgeKey {@code edge_key} to {@code road_segment.id}, 0 for edges without segment */
    record LoadedGraph(long versionId, NetworkGraphHopper hopper, long[] segmentByEdgeKey, BBox bounds) {
        long segmentId(int edgeKey) {
            return edgeKey >= 0 && edgeKey < segmentByEdgeKey.length ? segmentByEdgeKey[edgeKey] : 0;
        }
    }

    private record Entry(RegionStatus.State state, @Nullable LoadedGraph graph) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

    void importing(String region) {
        entries.compute(region, (key, entry) -> new Entry(RegionStatus.State.IMPORTING, graph(entry)));
    }

    void failed(String region) {
        entries.compute(region, (key, entry) -> new Entry(RegionStatus.State.FAILED, graph(entry)));
    }

    /** Uses the graph from now on, closes the previous one and returns its version. */
    Optional<Long> switchTo(String region, LoadedGraph graph) {
        Lock lock = lock(region).writeLock();
        lock.lock();
        try {
            LoadedGraph previous = graph(entries.put(region, new Entry(RegionStatus.State.READY, graph)));
            if (previous == null || previous.hopper() == graph.hopper()) {
                return Optional.empty();
            }
            previous.hopper().close();
            return previous.versionId() == graph.versionId() ? Optional.empty() : Optional.of(previous.versionId());
        } finally {
            lock.unlock();
        }
    }

    <T> T withGraph(String region, Function<LoadedGraph, T> action) {
        Lock lock = lock(region).readLock();
        lock.lock();
        try {
            LoadedGraph graph = graph(entries.get(region));
            if (graph == null) {
                throw new RoutingUnavailableException("region " + region + " has no graph yet");
            }
            return action.apply(graph);
        } finally {
            lock.unlock();
        }
    }

    /** First region with a graph whose bounds contain all points. */
    Optional<String> regionContaining(List<GeoPoint> points) {
        return entries.entrySet().stream()
                .filter(e -> e.getValue().graph() != null)
                .filter(e -> points.stream().allMatch(p -> e.getValue().graph().bounds().contains(p.lat(), p.lon())))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    boolean anyReady() {
        return entries.values().stream().anyMatch(entry -> entry.graph() != null);
    }

    RegionStatus status(String region) {
        Entry entry = entries.get(region);
        if (entry == null) {
            return new RegionStatus(region, RegionStatus.State.ABSENT, null);
        }
        return new RegionStatus(region, entry.state(), entry.graph() == null ? null : entry.graph().versionId());
    }

    @PreDestroy
    void close() {
        entries.values().stream().map(Graphs::graph).filter(graph -> graph != null)
                .forEach(graph -> graph.hopper().close());
        entries.clear();
    }

    private ReadWriteLock lock(String region) {
        return locks.computeIfAbsent(region, key -> new ReentrantReadWriteLock());
    }

    @Nullable
    private static LoadedGraph graph(@Nullable Entry entry) {
        return entry == null ? null : entry.graph();
    }
}