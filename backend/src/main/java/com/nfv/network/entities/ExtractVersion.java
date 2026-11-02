package com.nfv.network.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

import java.time.OffsetDateTime;

/** One import of the OSM extract of a region, see Docs/network.md. */
@Getter
@Entity
public class ExtractVersion extends AbstractEntity {
    @Column(nullable = false, length = 16)
    private String region;
    /** Timestamp of the OSM data from the file header, unknown for some files. */
    @Nullable
    private OffsetDateTime dataDate;
    @Column(nullable = false)
    private OffsetDateTime startedAt;
    @Nullable
    private OffsetDateTime finishedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExtractStatus status;
    @Nullable
    private Integer segmentCount;

    protected ExtractVersion() {
    }

    public ExtractVersion(String region, OffsetDateTime startedAt) {
        this.region = region;
        this.startedAt = startedAt;
        this.status = ExtractStatus.IMPORTING;
    }

    public void ready(@Nullable OffsetDateTime dataDate, int segmentCount, OffsetDateTime finishedAt) {
        this.dataDate = dataDate;
        this.segmentCount = segmentCount;
        this.finishedAt = finishedAt;
        this.status = ExtractStatus.READY;
    }

    public void failed(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
        this.status = ExtractStatus.FAILED;
    }
}