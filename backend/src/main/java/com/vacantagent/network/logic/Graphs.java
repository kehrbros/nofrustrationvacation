package com.vacantagent.network.logic;

import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The graph in use per region and the state of its import. */
@Component
class Graphs {
    private record Entry(RegionStatus.State state, @Nullable Long versionId, @Nullable NetworkGraphHopper hopper) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    void importing(String region) {
        entries.compute(region, (key, entry) -> entry == null
                ? new Entry(RegionStatus.State.IMPORTING, null, null)
                : new Entry(RegionStatus.State.IMPORTING, entry.versionId(), entry.hopper()));
    }

    void failed(String region) {
        entries.compute(region, (key, entry) -> entry == null
                ? new Entry(RegionStatus.State.FAILED, null, null)
                : new Entry(RegionStatus.State.FAILED, entry.versionId(), entry.hopper()));
    }

    /** Uses the graph from now on, closes the previous one and returns its version. */
    Optional<Long> switchTo(String region, long versionId, NetworkGraphHopper hopper) {
        Entry previous = entries.put(region, new Entry(RegionStatus.State.READY, versionId, hopper));
        if (previous == null || previous.hopper() == null || previous.hopper() == hopper) {
            return Optional.empty();
        }
        previous.hopper().close();
        return previous.versionId() == versionId ? Optional.empty() : Optional.ofNullable(previous.versionId());
    }

    Optional<NetworkGraphHopper> current(String region) {
        return Optional.ofNullable(entries.get(region)).map(Entry::hopper);
    }

    RegionStatus status(String region) {
        Entry entry = entries.get(region);
        return entry == null ? new RegionStatus(region, RegionStatus.State.ABSENT, null)
                : new RegionStatus(region, entry.state(), entry.versionId());
    }

    @PreDestroy
    void close() {
        entries.values().stream().map(Entry::hopper).filter(hopper -> hopper != null).forEach(NetworkGraphHopper::close);
        entries.clear();
    }
}