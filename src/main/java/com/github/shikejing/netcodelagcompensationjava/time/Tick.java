package com.github.shikejing.netcodelagcompensationjava.time;

/**
 * tick 与纳秒换算。
 * nanosToTick 采用四舍五入：时间戳映射到最近的 tick，
 * 避免 1/60s 等除不尽步长下整数截断导致的整体错位。
 */
public final class Tick {
    private Tick() {}

    public static long nanosToTick(long nanos, int tickRate) {
        return Math.round((double) nanos * (double) tickRate / 1_000_000_000.0d);
    }

    public static long tickToNanos(long tick, int tickRate) {
        return tick * 1_000_000_000L / tickRate;
    }
}
