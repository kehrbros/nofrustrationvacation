package com.nfv.advisor.entities;

/** Who started a run of the agent. */
public enum RunTrigger {
    /** Started by an assessment with {@code TOO_LATE}. */
    AUTO,
    /** Started by the user. */
    USER
}
