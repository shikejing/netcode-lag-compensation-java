package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.engine.GameServer;
import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import com.github.shikejing.netcodelagcompensationjava.snapshot.InterpResult;
import com.github.shikejing.netcodelagcompensationjava.snapshot.InterpStatus;
import com.github.shikejing.netcodelagcompensationjava.snapshot.SnapshotHistory;
import com.github.shikejing.netcodelagcompensationjava.snapshot.SnapshotInterpolator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interpolation window boundaries: real interpolation between two received
 * snapshots, explicit statuses for gaps / behind-history / awaiting, never a
 * silent static or zero position.
 */
class SnapshotInterpolatorTest {

    private final ServerConfig config = ServerConfig.defaults();
    private final long dt = config.tickDurationMs();

    private GameServer serverWithTicks(int ticks) {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.advanceTo(0);
        for (int t = 1; t <= ticks; t++) {
            long time = t * dt;
            server.ingest(new InputCommand(1, t, time, InputCommand.TICK_UNASSIGNED,
                    new Vec2(1.0, 0.0), false, 0.0));
            server.advanceTo(t);
        }
        return server;
    }

    @Test
    void interpolatesLinearlyBetweenTwoReceivedSnapshots() {
        GameServer server = serverWithTicks(5);
        SnapshotInterpolator interp = new SnapshotInterpolator(dt, config.interpolationDelayTicks());

        // Midway between tick 1 (time=50, x=-39) and tick 2 (time=100, x=-38): t=75
        InterpResult r = interp.query(server.history(), 75L);
        assertEquals(InterpStatus.OK, r.status(), r.reason());
        assertEquals(-38.5, r.players().get(1).position().x(), 1e-9);
        assertEquals(1L, r.lowerTick());
        assertEquals(2L, r.upperTick());
        assertEquals(0.5, r.alpha(), 1e-9);
    }

    @Test
    void awaitingStatusWhenAtOrAfterNewestSnapshot() {
        GameServer server = serverWithTicks(3);
        SnapshotInterpolator interp = new SnapshotInterpolator(dt, 2);
        long newestTime = server.history().latest().serverTimeMs();
        InterpResult r = interp.query(server.history(), newestTime + 30);
        assertEquals(InterpStatus.AWAITING_SNAPSHOT, r.status());
        assertTrue(r.players().isEmpty(), "no invented positions while awaiting");
        assertTrue(r.reason().contains("extrapolation is not performed"));

        // The render-time helper stays in the past by exactly the delay window.
        assertEquals(newestTime - 2L * dt, interp.targetServerTimeMs(newestTime));
    }

    @Test
    void behindHistoryAfterOldSnapshotsPruned() {
        GameServer server = serverWithTicks(config.snapshotRetainTicks() + 10);
        SnapshotInterpolator interp = new SnapshotInterpolator(dt, 0);
        long oldestTime = server.history().oldest().serverTimeMs();
        InterpResult r = interp.query(server.history(), oldestTime - dt);
        assertEquals(InterpStatus.BEHIND_HISTORY, r.status());
        assertTrue(r.players().isEmpty());
        assertTrue(r.reason().contains("older than oldest retained tick"));
    }

    @Test
    void missingSnapshotIsReportedAsGapNotAsStaticOrZero() {
        SnapshotHistory history = new SnapshotHistory(100);
        // Hand-place only tick 1 and tick 3; tick 2 is missing.
        history.append(snapshotAt(1));
        history.append(snapshotAt(3));
        SnapshotInterpolator interp = new SnapshotInterpolator(dt, 0);

        // Query that needs the missing tick 2.
        InterpResult r = interp.query(history, 2 * dt);
        assertEquals(InterpStatus.GAP, r.status());
        assertTrue(r.players().isEmpty(),
                "a missing snapshot must never be rendered as players at origin/standing still");
        assertTrue(r.reason().contains("missing bracketing snapshot"));
    }

    private com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot snapshotAt(long tick) {
        var ps = new com.github.shikejing.netcodelagcompensationjava.model.PlayerState(
                1, new Vec2(-40.0 + tick, 0.0), 0.0, 100, tick);
        return new com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot(
                tick, tick * dt, java.util.Map.of(1, ps), 0L, java.util.List.of());
    }
}
