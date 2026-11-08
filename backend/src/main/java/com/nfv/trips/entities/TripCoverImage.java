package com.nfv.trips.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.annotation.Nullable;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Title photo of a {@link Trip}, at most one per trip. Own table so that lists of trips do not load the image data,
 * not historized (a replaced image is gone). Deleted together with the trip. Carries a reduced copy for lists of
 * trips ({@code thumbnail…}), empty if the photo cannot be decoded or was stored before thumbnails existed.
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
    @Nullable
    @Column(length = 64)
    private String thumbnailContentType;
    @Nullable
    @Basic(fetch = FetchType.LAZY)
    private byte[] thumbnailContent;

    protected TripCoverImage() {
    }

    public TripCoverImage(long tripId, CoverImageSource source, String contentType, byte[] content,
                          OffsetDateTime createdAt, @Nullable Thumbnail thumbnail) {
        this.tripId = tripId;
        replace(source, contentType, content, createdAt, thumbnail);
    }

    /** New image, e.g. the user uploads one in place of the generated image. Replaces the thumbnail as well. */
    public void replace(CoverImageSource source, String contentType, byte[] content, OffsetDateTime createdAt,
                        @Nullable Thumbnail thumbnail) {
        this.source = source;
        this.contentType = contentType;
        this.content = content;
        this.createdAt = createdAt;
        setThumbnail(thumbnail);
    }

    /** Thumbnail computed later for the current image, e.g. for photos stored before thumbnails existed. */
    public void setThumbnail(@Nullable Thumbnail thumbnail) {
        this.thumbnailContentType = thumbnail == null ? null : thumbnail.contentType();
        this.thumbnailContent = thumbnail == null ? null : thumbnail.content();
    }

    /** Reduced copy if there is one. */
    public Optional<Thumbnail> thumbnail() {
        return thumbnailContent == null || thumbnailContentType == null
                ? Optional.empty() : Optional.of(new Thumbnail(thumbnailContentType, thumbnailContent));
    }

    /** Reduced copy of the image for lists of trips. */
    public record Thumbnail(String contentType, byte[] content) {
    }
}
