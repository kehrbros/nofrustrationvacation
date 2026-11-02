package com.nfv.trips.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Title photo of a {@link Trip}, at most one per trip. Own table so that lists of trips do not load the image data,
 * not historized (a replaced image is gone). Deleted together with the trip.
 */
@Getter
@Entity
public class TripCoverImage extends AbstractEntity {
    @Column(nullable = false, unique = true)
    private long tripId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CoverImageSource source;
    /** Media type, e.g. {@code image/jpeg}. */
    @Column(nullable = false, length = 64)
    private String contentType;
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] content;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected TripCoverImage() {
    }

    public TripCoverImage(long tripId, CoverImageSource source, String contentType, byte[] content,
                          OffsetDateTime createdAt) {
        this.tripId = tripId;
        replace(source, contentType, content, createdAt);
    }

    /** New image, e.g. the user uploads one in place of the generated image. */
    public void replace(CoverImageSource source, String contentType, byte[] content, OffsetDateTime createdAt) {
        this.source = source;
        this.contentType = contentType;
        this.content = content;
        this.createdAt = createdAt;
    }
}
