package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.engine.GameServer;
import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replaying a retained tick must return exactly the snapshot produced by a
 * direct run to that tick, and the pruning boundary must be deterministic.
 */
class ReplayConsistencyTest {

    private final ServerConfig config = ServerConfig.defaults();

    private InputCommand move(int client, int seq, long time, double x, double y, boolean fire) {
        return new InputCommand(client, seq, time, InputCommand.TICK_UNASSIGNED,
                new Vec2(x, y), fire, 0.0);
    }

    private List<WorldSnapshot> drive(GameServer server, int ticks) {
        List<WorldSnapshot> all = new ArrayList<>();
        all.add(server.advanceTo(0).get(0));
        for (int t = 1; t <= ticks; t++) {
            long time = t * config.tickDurationMs();
            server.ingest(move(1, t, time, 1.0, 0.0, t == 5));
            server.ingest(move(2, t, time, -0.4, 0.2, false));
            all.add(server.advanceTo(t).get(0));
        }
        return all;
    }

    @Test
    void storedSnapshotAtEveryRetainedTickEqualsDirectRun() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.registerClient(2);
        List<WorldSnapshot> direct = drive(server, 30);

        for (WorldSnapshot expected : direct) {
            WorldSnapshot replayed = server.history().at(expected.tick());
            if (replayed != null) {
                WorldDeterminismTest.assertSnapshotEquals(expected, replayed);
            }
        }
    }

    @Test
    void pruningBoundaryIsExactAndOldTicksAreUnavailable() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        drive(server, config.snapshotRetainTicks() + 20);

        long latest = server.history().latest().tick();
        long floor = latest - config.snapshotRetainTicks();
        assertNotNull(server.history().at(floor), "tick exactly at the retention bound is kept");
        assertNull(server.history().at(floor - 1), "ticks strictly older than the bound are pruned");
        assertTrue(server.history().size() <= config.snapshotRetainTicks() + 1);
        assertFalse(server.history().hasGap(floor, floor + 1), "consecutive retained ticks have no gap");
    }

    @Test
    void replayFromRestoredStateMatchesContinuedDirectRun() {
        GameServer first = new GameServer(config);
        first.registerClient(1);
        first.registerClient(2);
        List<WorldSnapshot> direct = drive(first, 25);
        var state = first.exportState();

        GameServer restored = new GameServer(config, state);
        assertEquals(25L, restored.currentSnapshot().tick());
        WorldDeterminismTest.assertSnapshotEquals(direct.get(25), restored.currentSnapshot());

        for (int t = 26; t <= 35; t++) {
            long time = t * config.tickDurationMs();
            restored.ingest(move(1, t, time, 1.0, 0.0, false));
            restored.ingest(move(2, t, time, -0.4, 0.2, false));
            restored.advanceTo(t);
        }

        GameServer compare = new GameServer(config);
        compare.registerClient(1);
        compare.registerClient(2);
        List<WorldSnapshot> continued = drive(compare, 35);
        WorldDeterminismTest.assertSnapshotEquals(
                continued.get(35), restored.currentSnapshot());
    }
}
