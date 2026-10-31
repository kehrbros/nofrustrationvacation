package com.vacantagent.network.logic;

import jakarta.annotation.Nullable;

/** State of a region for routing: {@code extractVersionId} is the version in use, if any. */
public record RegionStatus(String region, State state, @Nullable Long extractVersionId) {
    public enum State {
        /** No graph yet. */
        ABSENT,
        /** Import running, an older graph may still be in use. */
        IMPORTING,
        READY,
        /** Last import failed, an older graph may still be in use. */
        FAILED
    }
}