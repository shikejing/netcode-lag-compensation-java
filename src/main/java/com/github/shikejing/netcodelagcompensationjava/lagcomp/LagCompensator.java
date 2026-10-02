package com.github.shikejing.netcodelagcompensationjava.lagcomp;

import com.github.shikejing.netcodelagcompensationjava.snapshot.EntityState;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;
import com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator;
import com.github.shikejing.netcodelagcompensationjava.world.World;

/**
 * 延迟补偿命中判定。
 *
 * 把世界按射手的客户端时刻回退到对应 tick 再做命中扫描。
 * 回退窗口有上界（maxRewindTicks）：超过上界的请求返回 TOO_LATE，
 * 与 HIT/MISS/INVALID 明确区分，绝不静默接受或拒绝。
 */
public class LagCompensator {

    public record ShotRequest(String shooterId, long clientTimeNanos, double aimX, double aimY) { }

    private final int maxRewindTicks;

    public LagCompensator(int maxRewindTicks) {
        if (maxRewindTicks < 0) throw new IllegalArgumentException("maxRewindTicks must be >= 0");
        this.maxRewindTicks = maxRewindTicks;
    }

    public int maxRewindTicks() { return maxRewindTicks; }

    public HitRecord evaluate(ShotRequest request, long currentTick, long hitId,
                              ClockEstimator clock, Rewinder rewinder, int tickRate) {
        long mappedTick = clock.rewindTick(request.clientTimeNanos(), currentTick, tickRate);
        long age = currentTick - mappedTick;

        if (age < 0) {
            return new HitRecord(hitId, currentTick, mappedTick, request.clientTimeNanos(),
                    request.shooterId(), null, HitStatus.INVALID,
                    Double.NaN, Double.NaN, request.aimX(), request.aimY(),
                    "mapped tick " + mappedTick + " is in the future relative to current tick " + currentTick);
        }
        if (age > maxRewindTicks) {
            return new HitRecord(hitId, currentTick, mappedTick, request.clientTimeNanos(),
                    request.shooterId(), null, HitStatus.TOO_LATE,
                    Double.NaN, Double.NaN, request.aimX(), request.aimY(),
                    "rewind age " + age + " ticks exceeds max rewind window " + maxRewindTicks
                            + " (mappedTick=" + mappedTick + ", currentTick=" + currentTick + ")");
        }

        WorldSnapshot world = rewinder.rewindTo(mappedTick);
        if (world == null) {
            return new HitRecord(hitId, currentTick, mappedTick, request.clientTimeNanos(),
                    request.shooterId(), null, HitStatus.INVALID,
                    Double.NaN, Double.NaN, request.aimX(), request.aimY(),
                    "no world state available for tick " + mappedTick);
        }
        EntityState shooter = world.entity(request.shooterId());
        if (shooter == null || !shooter.alive()) {
            return new HitRecord(hitId, currentTick, mappedTick, request.clientTimeNanos(),
                    request.shooterId(), null, HitStatus.INVALID,
                    shooter == null ? Double.NaN : shooter.x(), shooter == null ? Double.NaN : shooter.y(),
                    request.aimX(), request.aimY(),
                    "shooter not alive or absent at rewind tick " + mappedTick);
        }

        World.HitscanResult scan = World.hitscan(world, request.shooterId(), request.aimX(), request.aimY());
        if (scan.hit()) {
            return new HitRecord(hitId, currentTick, mappedTick, request.clientTimeNanos(),
                    request.shooterId(), scan.victimId(), HitStatus.HIT,
                    shooter.x(), shooter.y(), request.aimX(), request.aimY(),
                    "hit " + scan.victimId() + " at rewind tick " + mappedTick
                            + " (point=" + scan.pointX() + "," + scan.pointY() + ")");
        }
        return new HitRecord(hitId, currentTick, mappedTick, request.clientTimeNanos(),
                request.shooterId(), null, HitStatus.MISS,
                shooter.x(), shooter.y(), request.aimX(), request.aimY(),
                "no hit at rewind tick " + mappedTick);
    }
}
