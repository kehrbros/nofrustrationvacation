package com.nfv.incidents.logic;

import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.entities.VehicleScope;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * An incident as a source reports it, before it is located, see Docs/traffic-feed.md#processing-trafficeventprocessor.
 *
 * @param description   shown to users, at most 2000 characters are kept
 * @param validTo       {@code null}: until further notice
 * @param expectedDelay delay given by the source as a figure
 * @param assumedDelay  delay the source assumes without a figure (estimated)
 * @param location      {@code null} if the source gave none ({@code NO_SEGMENTS})
 * @param roadRef       road number as the source names it, e.g. {@code 01N}; only segments with that number
 * @param direction     {@code null} like {@link TravelDirection#BOTH}
 * @param networkRegion region of the road network to locate the incident in
 * @param ended         the source reports the end (or the event disappeared): the incident ends now
 * @param windows       periods within the validity in which the incident applies, empty: the whole validity
 * @param vehicleScope  vehicles the incident applies to, {@code null} like {@link VehicleScope#ALL}
 * @param timesUnconfirmed closure whose times are only in the text and could not be read yet
 * @param linkOnly      the event concerns only a ramp or link, located on those segments only
 */
public record ReportedIncident(IncidentType type, @Nullable String description, OffsetDateTime validFrom,
                               @Nullable OffsetDateTime validTo, @Nullable Duration expectedDelay,
                               @Nullable Duration assumedDelay, @Nullable IncidentLocation location,
                               @Nullable String roadRef, @Nullable TravelDirection direction, String networkRegion,
                               boolean ended, List<TimeWindow> windows, @Nullable VehicleScope vehicleScope,
                               boolean timesUnconfirmed, boolean linkOnly) {
    public ReportedIncident {
        windows = List.copyOf(windows);
        vehicleScope = vehicleScope == null ? VehicleScope.ALL : vehicleScope;
    }
}
