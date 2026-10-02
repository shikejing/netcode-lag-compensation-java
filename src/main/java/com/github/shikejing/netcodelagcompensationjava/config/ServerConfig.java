package com.github.shikejing.netcodelagcompensationjava.config;

/**
 * Immutable server configuration (all timing values in milliseconds unless noted).
 */
public final class ServerConfig {

    private final long tickDurationMs;
    private final int interpolationDelayTicks;
    private final int snapshotRetainTicks;
    private final int maxRewindTicks;
    private final int maxFutureInputTicks;
    private final int maxInputQueuePerClient;
    private final double arenaHalfSize;
    private final double playerRadius;
    private final double hitRadius;
    private final double fireDamage;
    private final long rngSeed;

    public ServerConfig(long tickDurationMs,
                        int interpolationDelayTicks,
                        int snapshotRetainTicks,
                        int maxRewindTicks,
                        int maxFutureInputTicks,
                        int maxInputQueuePerClient,
                        double arenaHalfSize,
                        double playerRadius,
                        double hitRadius,
                        double fireDamage,
                        long rngSeed) {
        this.tickDurationMs = tickDurationMs;
        this.interpolationDelayTicks = interpolationDelayTicks;
        this.snapshotRetainTicks = snapshotRetainTicks;
        this.maxRewindTicks = maxRewindTicks;
        this.maxFutureInputTicks = maxFutureInputTicks;
        this.maxInputQueuePerClient = maxInputQueuePerClient;
        this.arenaHalfSize = arenaHalfSize;
        this.playerRadius = playerRadius;
        this.hitRadius = hitRadius;
        this.fireDamage = fireDamage;
        this.rngSeed = rngSeed;
    }

    public static ServerConfig defaults() {
        // 50 ms fixed tick | 2-tick interp delay | 5 s snapshot memory |
        // 500 ms max rewind | 500 ms future-input tolerance | 128 queued inputs/client |
        // 100-unit arena | player radius 1 | hitscan hit radius 0.8 | 25 damage | seed 0x9E3779B97F4A7C15
        return new ServerConfig(50L, 2, 100, 10, 10, 128,
                100.0, 1.0, 0.8, 25.0, 0x9E3779B97F4A7C15L);
    }

    public long tickDurationMs() { return tickDurationMs; }
    public int interpolationDelayTicks() { return interpolationDelayTicks; }
    public int snapshotRetainTicks() { return snapshotRetainTicks; }
    public int maxRewindTicks() { return maxRewindTicks; }
    public int maxFutureInputTicks() { return maxFutureInputTicks; }
    public int maxInputQueuePerClient() { return maxInputQueuePerClient; }
    public double arenaHalfSize() { return arenaHalfSize; }
    public double playerRadius() { return playerRadius; }
    public double hitRadius() { return hitRadius; }
    public double fireDamage() { return fireDamage; }
    public long rngSeed() { return rngSeed; }
}
