package com.nfv.incidents.logic;

import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.entities.VehicleScope;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything of an incident except its segments, for {@code IncidentLogic.create}/{@code change}.
 *
 * @param validTo       {@code null}: until further notice
 * @param expectedDelay delay given as a figure
 * @param assumedDelay  delay the source assumes without a figure (estimated)
 * @param windows       periods within the validity in which the incident applies, empty: the whole validity
 * @param vehicleScope  vehicles the incident applies to
 * @param timesUnconfirmed closure whose times are only in the text and could not be read yet
 */
public record IncidentDetails(IncidentType type, @Nullable String description, OffsetDateTime validFrom,
                              @Nullable OffsetDateTime validTo, @Nullable Duration expectedDelay,
                              @Nullable Duration assumedDelay, List<TimeWindow> windows, VehicleScope vehicleScope,
                              boolean timesUnconfirmed) {
    public IncidentDetails {
        windows = List.copyOf(windows);
    }

    /** Without assumption, windows or vehicle restriction. */
    public static IncidentDetails of(IncidentType type, @Nullable String description, OffsetDateTime validFrom,
                                     @Nullable OffsetDateTime validTo, @Nullable Duration expectedDelay) {
        return new IncidentDetails(type, description, validFrom, validTo, expectedDelay, null, List.of(),
                VehicleScope.ALL, false);
    }
}
