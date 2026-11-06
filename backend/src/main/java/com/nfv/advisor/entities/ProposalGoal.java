package com.nfv.advisor.entities;

/** What the user wants most from a run. */
public enum ProposalGoal {
    /** Reach the target stop by its arrival time. */
    ARRIVE_ON_TIME,
    /** Lose as little time as possible, even if the arrival time is missed. */
    MINIMIZE_DELAY
}
