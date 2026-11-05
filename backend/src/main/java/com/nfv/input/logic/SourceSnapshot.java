package com.nfv.input.logic;

import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Result of one {@link TrafficSource#fetch}.
 *
 * @param items        events of the response
 * @param complete     the response is the whole current state of the source; only then missing events are cleared
 * @param etag         {@code ETag} of the response, if any
 * @param lastModified {@code Last-Modified} of the response, if any
 * @param notModified  the source answered "not modified" (HTTP 304), {@code items} is empty and means nothing
 */
public record SourceSnapshot(List<SourceItem> items, boolean complete, @Nullable String etag,
                             @Nullable String lastModified, boolean notModified) {
    public SourceSnapshot {
        items = List.copyOf(items);
    }

    public static SourceSnapshot complete(List<SourceItem> items, @Nullable String etag,
                                          @Nullable String lastModified) {
        return new SourceSnapshot(items, true, etag, lastModified, false);
    }

    /** The source answered "not modified". */
    public static SourceSnapshot unchanged() {
        return new SourceSnapshot(List.of(), false, null, null, true);
    }
}
