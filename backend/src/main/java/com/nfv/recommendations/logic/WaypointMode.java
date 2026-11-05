package com.nfv.recommendations.logic;

/** How suggestions treat the waypoints of the user. */
public enum WaypointMode {
    /** Only start and end stop, the waypoints are dropped when a suggestion is applied. */
    IGNORE,
    /** Through all waypoints, alternatives per leg between them. */
    FOLLOW
}