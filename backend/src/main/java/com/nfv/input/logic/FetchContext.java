package com.nfv.input.logic;

import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/**
 * State of the previous poll, passed to {@link TrafficSource#fetch}.
 *
 * @param etag         {@code ETag} of the last response, to send as {@code If-None-Match}
 * @param lastModified {@code Last-Modified} of the last response, to send as {@code If-Modified-Since}
 * @param now          time of this poll
 * @param location     other address than the configured one for this poll only (tests: another fixture file)
 */
public record FetchContext(@Nullable String etag, @Nullable String lastModified, OffsetDateTime now,
                           @Nullable String location) {
}
