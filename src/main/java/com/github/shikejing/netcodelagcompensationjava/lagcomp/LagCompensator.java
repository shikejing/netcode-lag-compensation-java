package com.github.shikejing.netcodelagcompensationjava.lagcomp;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.model.HitVerdict;
import com.github.shikejing.netcodelagcompensationjava.model.ShotQuery;
import com.github.shikejing.netcodelagcompensationjava.model.ShotResult;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;
import com.github.shikejing.netcodelagcompensationjava.simulation.World;
import com.github.shikejing.netcodelagcompensationjava.snapshot.SnapshotHistory;
import com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator;

/**
 * Rewinds the authoritative world to the shooter's perceived moment before
 * running the hitscan test.
 *
 * <p>The shooter's input timestamp (on its own clock) is mapped onto the
 * server timeline by the smoothed, monotone {@link ClockEstimator}, then
 * quantized to an authoritative tick. The rewind window has a hard upper bound
 * ({@code maxRewindTicks}):
 * <ul>
 *   <li>older than {@code currentTick - maxRewindTicks} (or before retained
 *       history) -> {@link HitVerdict#REJECTED_BEYOND_WINDOW};</li>
 *   <li>at or after the current tick -> {@link HitVerdict#REJECTED_FUTURE};</li>
 *   <li>within the window -> a real rewind and {@link HitVerdict#HIT}/
 *       {@link HitVerdict#MISS} with full trace details.</li>
 * </ul>
 * An out-of-bounds request is therefore neither silently accepted nor silently
 * dropped: the caller receives an explicit, distinguishable conclusion.
 */
public final class LagCompensator {

    private final ServerConfig config;
    private final ClockEstimator clock;

    public LagCompensator(ServerConfig config, ClockEstimator clock) {
        this.config = config;
        this.clock = clock;
    }

    public ShotResult evaluate(ShotQuery query, SnapshotHistory history) {
        WorldSnapshot current = history.latest();
        if (current == null) {
            return new ShotResult(HitVerdict.REJECTED_BEYOND_WINDOW, -1,
                    -1L, -1L, -1L, 0L, Vec2.ZERO,
                    "no authoritative snapshot exists yet");
        }
        long currentTick = current.tick();
        long estimatedServerTimeMs = clock.toServerTimeMs(query.clientTimeMs());
        long requestedTick = Math.floorDiv(estimatedServerTimeMs, config.tickDurationMs());
        long oldestTick = history.oldest() != null ? history.oldest().tick() : currentTick;
        long windowFloorTick = currentTick - config.maxRewindTicks();

        if (requestedTick >= currentTick) {
            return new ShotResult(HitVerdict.REJECTED_FUTURE, -1,
                    requestedTick, -1L, currentTick, 0L, Vec2.ZERO,
                    "shooter time maps to tick " + requestedTick
                            + " which is at/after the authoritative tick " + currentTick
                            + "; cannot rewind into the future");
        }
        if (requestedTick < windowFloorTick || requestedTick < oldestTick) {
            long effectiveFloor = Math.max(windowFloorTick, oldestTick);
            String reason = requestedTick < oldestTick
                    ? "requested tick " + requestedTick + " predates retained history (oldest="
                            + oldestTick + ")"
                    : "requested tick " + requestedTick + " exceeds the rewind bound of "
                            + config.maxRewindTicks() + " ticks (floor=" + windowFloorTick + ")";
            return new ShotResult(HitVerdict.REJECTED_BEYOND_WINDOW, -1,
                    requestedTick, effectiveFloor, currentTick,
                    currentTick - requestedTick, Vec2.ZERO, reason);
        }

        WorldSnapshot rewound = history.at(requestedTick);
        if (rewound == null) {
            return new ShotResult(HitVerdict.REJECTED_BEYOND_WINDOW, -1,
                    requestedTick, -1L, currentTick, currentTick - requestedTick, Vec2.ZERO,
                    "snapshot for tick " + requestedTick + " is missing within the window");
        }

        Vec2 origin = query.origin();
        if (rewound.player(query.shooterId()) != null) {
            origin = rewound.player(query.shooterId()).position();
        }
        double effectiveRadius = config.playerRadius() + config.hitRadius();
        World.TraceResult trace = World.traceHitscan(
                rewound, query.shooterId(), origin, query.direction(), effectiveRadius);

        if (trace.targetId() < 0) {
            return new ShotResult(HitVerdict.MISS, -1,
                    requestedTick, rewound.tick(), currentTick,
                    currentTick - requestedTick, Vec2.ZERO,
                    "ray from " + origin + " missed at rewound tick " + rewound.tick());
        }
        return new ShotResult(HitVerdict.HIT, trace.targetId(),
                requestedTick, rewound.tick(), currentTick,
                currentTick - requestedTick, trace.hitPoint(),
                "hit target " + trace.targetId() + " at " + trace.hitPoint()
                        + " after rewinding " + (currentTick - requestedTick) + " tick(s)");
    }
}
