package com.nfv.network.logic;

/** Driven part of a segment, from 0 (its start) to 1 (its end), e.g. a section of a planned route. */
public record SectionPart(long segmentId, double fromFraction, double toFraction) {
}
