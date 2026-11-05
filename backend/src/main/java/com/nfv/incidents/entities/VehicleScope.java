package com.nfv.incidents.entities;

/** Vehicles an {@link Incident} applies to, from the enrichment of the source text. */
public enum VehicleScope {
    ALL,
    /** Only heavy vehicles: no effect on cars, assessed as {@code NO_IMPACT}. */
    HEAVY_ONLY,
    LIGHT_ONLY
}
