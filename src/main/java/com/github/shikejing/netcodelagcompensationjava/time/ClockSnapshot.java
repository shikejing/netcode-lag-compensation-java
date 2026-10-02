package com.github.shikejing.netcodelagcompensationjava.time;

/**
 * Immutable view of the client->server clock estimation.
 */
public record ClockSnapshot(
        long clientTimeMs,
        long serverTimeEstimateMs,
        long rttRawMs,
        double rttSmoothedMs,
        long offsetSmoothedMs,
        long adjustments,
        boolean clamped
) {
}
