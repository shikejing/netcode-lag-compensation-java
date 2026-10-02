package com.github.shikejing.netcodelagcompensationjava.model;

/**
 * Result of a lag-compensated shot: explicit verdict plus a fully traceable
 * explanation of which tick was rewound to and how far back that is.
 */
public record ShotResult(
        HitVerdict verdict,
        int targetId,
        long requestedTick,
        long rewoundTick,
        long currentTick,
        long rewindAgeTicks,
        Vec2 hitPoint,
        String reason
) {
}
