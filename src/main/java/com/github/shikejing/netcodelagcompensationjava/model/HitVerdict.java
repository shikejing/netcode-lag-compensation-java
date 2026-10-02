package com.github.shikejing.netcodelagcompensationjava.model;

/**
 * Distinguishable conclusions of a lag-compensated hitscan evaluation.
 */
public enum HitVerdict {
    HIT,
    MISS,
    /** Requested rewind tick is older than the bounded rewind window / no history. */
    REJECTED_BEYOND_WINDOW,
    /** Input claims a time ahead of the current authoritative tick. */
    REJECTED_FUTURE
}
