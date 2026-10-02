package com.github.shikejing.netcodelagcompensationjava.model;

/**
 * A timestamped client input. {@code clientTimeMs} is on the client clock;
 * {@code targetTick} is assigned by the server (-1 before assignment).
 */
public record InputCommand(
        int clientId,
        int sequence,
        long clientTimeMs,
        long targetTick,
        Vec2 move,
        boolean fire,
        double aimAngle
) {
    public static final long TICK_UNASSIGNED = -1L;

    public InputCommand withTargetTick(long tick) {
        return new InputCommand(clientId, sequence, clientTimeMs, tick, move, fire, aimAngle);
    }
}
