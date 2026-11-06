package com.nfv.advisor.entities;

/** State of a run of the agent. */
public enum RunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    /** The model was not available: the best calculated options, without reasons. */
    COMPLETED_WITHOUT_MODEL,
    FAILED
}
