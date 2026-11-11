package com.nfv.network.logic;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Stores the places of a region, see Docs/network.md#places. */
@Component
@RequiredArgsConstructor
class PlaceWriter {
    private static final int BATCH = 1_000;

    private final JdbcTemplate jdbc;

    /** Replaces all places of the region at once, so queries never see two versions and need no version filter. */
    @Transactional
    public void replace(String region, long versionId, List<Place> places) {
        jdbc.update("DELETE FROM place WHERE region = ?", region);
        jdbc.batchUpdate("""
                INSERT INTO place (region, extract_version_id, name, kind, geometry)
                VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))""",
                places, BATCH, (statement, place) -> {
                    statement.setString(1, region);
                    statement.setLong(2, versionId);
                    statement.setString(3, place.name());
                    statement.setString(4, place.kind().name());
                    statement.setDouble(5, place.point().lon());
                    statement.setDouble(6, place.point().lat());
                });
    }

    @Transactional(readOnly = true)
    public boolean hasPlaces(String region) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM place WHERE region = ?)", Boolean.class, region));
    }
}
