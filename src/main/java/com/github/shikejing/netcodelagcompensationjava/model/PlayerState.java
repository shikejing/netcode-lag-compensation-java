package com.github.shikejing.netcodelagcompensationjava.model;

/**
 * Authoritative per-player state at one tick.
 */
public record PlayerState(
        int clientId,
        Vec2 position,
        double aimAngle,
        int health,
        long lastProcessedSequence
) {
}
