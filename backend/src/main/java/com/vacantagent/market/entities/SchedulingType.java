package com.vacantagent.market.entities;

public enum SchedulingType {
    /** The calendar fields are the moment the scheduling fires. */
    FIXED,
    /** The calendar fields are the token refresh, the scheduling fires {@code leadTime} before it. */
    BEFORE_REFRESH
}