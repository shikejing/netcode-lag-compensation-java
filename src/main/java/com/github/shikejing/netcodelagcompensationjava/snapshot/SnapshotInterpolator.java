package com.github.shikejing.netcodelagcompensationjava.snapshot;

import com.github.shikejing.netcodelagcompensationjava.model.PlayerState;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * Interpolates observer-visible positions strictly between two *received*
 * authoritative snapshots. A fixed delay of {@code delayTicks} keeps the render
 * time safely in the past so a bracketing pair normally exists.
 *
 * <p>Boundary contract — nothing here is ever guessed:
 * <ul>
 *   <li>{@link InterpStatus#AWAITING_SNAPSHOT}: query time >= newest snapshot;
 *       never extrapolated, never frozen at the last known spot.</li>
 *   <li>{@link InterpStatus#BEHIND_HISTORY}: query time < oldest retained tick;
 *       the data was deliberately aged out.</li>
 *   <li>{@link InterpStatus#GAP}: the bracketing tick is absent (loss/reorder);
 *       a missing snapshot is never treated as "player standing still" or as
 *       the origin — the whole frame is flagged and carries no positions.</li>
 *   <li>{@link InterpStatus#OK}: real linear interpolation between consecutive
 *       received snapshots for players present in both.</li>
 * </ul>
 */
public final class SnapshotInterpolator {

    private final long tickDurationMs;
    private final int delayTicks;

    public SnapshotInterpolator(long tickDurationMs, int delayTicks) {
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0");
        }
        this.tickDurationMs = tickDurationMs;
        this.delayTicks = delayTicks;
    }

    /** Render time the observer should ask for, given the newest known server time. */
    public long targetServerTimeMs(long latestServerTimeMs) {
        return Math.max(0L, latestServerTimeMs - (long) delayTicks * tickDurationMs);
    }

    public int delayTicks() {
        return delayTicks;
    }

    public InterpResult query(SnapshotHistory history, long serverTimeMs) {
        WorldSnapshot newest = history.latest();
        WorldSnapshot oldest = history.oldest();
        if (newest == null) {
            return new InterpResult(InterpStatus.AWAITING_SNAPSHOT, serverTimeMs,
                    -1L, -1L, 0.0, Map.of(), "no snapshots received yet");
        }
        if (serverTimeMs >= newest.serverTimeMs()) {
            return new InterpResult(InterpStatus.AWAITING_SNAPSHOT, serverTimeMs,
                    newest.tick(), newest.tick(), 0.0, Map.of(),
                    "query time " + serverTimeMs + " is at/after newest snapshot "
                            + newest.serverTimeMs() + "; extrapolation is not performed");
        }
        if (oldest != null && serverTimeMs < oldest.serverTimeMs()) {
            return new InterpResult(InterpStatus.BEHIND_HISTORY, serverTimeMs,
                    oldest.tick(), oldest.tick(), 0.0, Map.of(),
                    "query time " + serverTimeMs + " is older than oldest retained tick "
                            + oldest.tick() + " (time=" + oldest.serverTimeMs() + ")");
        }

        // Locate the exact tick pair bracketing the query time. The server
        // timeline satisfies serverTimeMs == tick * tickDurationMs.
        double tickPosition = serverTimeMs / (double) tickDurationMs;
        long lowerTick = (long) Math.floor(tickPosition);
        long upperTick = lowerTick + 1;
        double alpha = tickPosition - lowerTick;
        if (alpha < 0.0) {
            alpha = 0.0;
        } else if (alpha > 1.0) {
            alpha = 1.0;
        }

        WorldSnapshot lower = history.at(lowerTick);
        WorldSnapshot upper = history.at(upperTick);
        if (lower == null || upper == null) {
            WorldSnapshot floor = history.atOrBefore(serverTimeMs / tickDurationMs);
            long floorTick = floor == null ? lowerTick : floor.tick();
            return new InterpResult(InterpStatus.GAP, serverTimeMs,
                    lowerTick, upperTick, alpha, Map.of(),
                    "missing bracketing snapshot(s) for ticks [" + lowerTick + "," + upperTick
                            + "]; a gap is never rendered as static/zero positions (last present tick="
                            + floorTick + ")");
        }
        if (history.hasGap(lowerTick, upperTick)) {
            return new InterpResult(InterpStatus.GAP, serverTimeMs,
                    lowerTick, upperTick, alpha, Map.of(),
                    "snapshot gap detected between ticks " + lowerTick + " and " + upperTick);
        }

        Map<Integer, PlayerState> interpolated = new HashMap<>();
        for (Map.Entry<Integer, PlayerState> e : lower.players().entrySet()) {
            PlayerState a = e.getValue();
            PlayerState b = upper.players().get(e.getKey());
            // Player present in only one endpoint: do not invent a position.
            if (b == null) {
                continue;
            }
            Vec2 pos = Vec2.lerp(a.position(), b.position(), alpha);
            double aim = lerpAngle(a.aimAngle(), b.aimAngle(), alpha);
            interpolated.put(e.getKey(), new PlayerState(e.getKey(), pos, aim,
                    b.health(), b.lastProcessedSequence()));
        }
        return new InterpResult(InterpStatus.OK, serverTimeMs,
                lowerTick, upperTick, alpha, interpolated, null);
    }

    private static double lerpAngle(double a, double b, double t) {
        double delta = ((b - a + Math.PI) % (2.0 * Math.PI)) - Math.PI;
        if (delta < -Math.PI) {
            delta += 2.0 * Math.PI;
        }
        return a + delta * t;
    }
}
