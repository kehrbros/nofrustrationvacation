package com.vacantagent.incidents.entities;

/** What an {@link Incident} means for a route. */
public enum Impact {
    /** The route cannot be driven as planned, e.g. road closure. */
    BLOCKED,
    /** The route can be driven, but takes longer. */
    DELAYED
}
