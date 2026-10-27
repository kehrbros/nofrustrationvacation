package com.vacantagent.market.entities;

public enum SchedulingInterval {
    /** Repeats every day at the given time. */
    DAILY,
    /** Repeats every week on the given weekday. */
    WEEKLY,
    /** Repeats every month on the given day of the month. */
    MONTHLY
}