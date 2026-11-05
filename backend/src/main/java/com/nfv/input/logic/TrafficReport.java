package com.nfv.input.logic;

import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentLocation;
import com.nfv.incidents.logic.TravelDirection;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * One event of a source read deterministically from its payload ({@link TrafficSource#read}), without network or
 * model.
 *
 * @param resolved     the source reports the end of the event
 * @param validTo      {@code null}: until further notice
 * @param roadRef      road number as the source names it, e.g. {@code 01N}
 * @param direction    {@code null} if the source gives none (both directions)
 * @param location     {@code null} if the event has no geometry
 * @param delay        delay as a figure, if the source gives one
 * @param assumedDelay delay the source assumes without a figure, {@code null} without assumption
 * @param title        short text, e.g. place and kind of event
 * @param description  longer text for users
 * @param messageText  all free texts, the same as {@link SourceItem#messageText()}
 * @param sourceUrl    page of the event at the source, if any
 * @param linkOnly     the event concerns only a ramp or link, not the road it belongs to
 */
public record TrafficReport(IncidentType type, boolean resolved, OffsetDateTime validFrom,
                            @Nullable OffsetDateTime validTo, @Nullable String roadRef,
                            @Nullable TravelDirection direction, @Nullable IncidentLocation location,
                            @Nullable Duration delay, @Nullable Duration assumedDelay, String title,
                            @Nullable String description, @Nullable String messageText, @Nullable String sourceUrl,
                            boolean linkOnly) {
}
