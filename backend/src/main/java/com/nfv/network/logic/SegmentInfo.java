package com.nfv.network.logic;

import jakarta.annotation.Nullable;

/**
 * Details of a road segment, see {@link SegmentQueries}.
 *
 * @param midpoint  point halfway along the segment, e.g. as via point that reproduces a way
 * @param reverseId segment of the opposite direction of the same edge, {@code null} on one-way roads
 */
public record SegmentInfo(long id, @Nullable String ref, @Nullable String name, double lengthMeters,
                          GeoPoint midpoint, @Nullable Long reverseId) {
}