package com.nfv.advisor.entities;

/** A step of a plan, in the order the plan runs, see Docs/incident-plans.md#bausteine-und-compose_plan. */
public enum PlanStepKind {
    /** The drive today: on the planned route, another way or another departure ({@code routeKind}). */
    DRIVE,
    /** A night at a hotel along the way ({@code stay}); the stop is added there. */
    OVERNIGHT,
    /** The drive on to the target after the night, checked against the incidents at that time. */
    CONTINUE,
    /** The accommodation booked at the target stays, it is told about the later arrival ({@code arrivalOn}). */
    KEEP_BOOKING,
    /** Another accommodation at the target stop ({@code stay}), the booked one goes on the to-do list. */
    REPLACE_STAY,
    /** The target stop moves to another place ({@code placeName}) with a hotel there ({@code stay}). */
    NEW_TARGET
}
