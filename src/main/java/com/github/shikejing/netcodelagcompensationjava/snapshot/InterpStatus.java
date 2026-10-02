package com.github.shikejing.netcodelagcompensationjava.snapshot;

/**
 * Outcome of an interpolation query. Missing/desynced data is reported as a
 * distinct status, never silently rendered as a static or zero position.
 */
public enum InterpStatus {
    /** Interpolated between two authoritative snapshots. */
    OK,
    /** Time is within history but there is a gap (missing tick) around the query time. */
    GAP,
    /** Time is before the oldest retained snapshot (history discarded). */
    BEHIND_HISTORY,
    /** Time is at/after the newest snapshot; no future bracketing pair exists. */
    AWAITING_SNAPSHOT
}
