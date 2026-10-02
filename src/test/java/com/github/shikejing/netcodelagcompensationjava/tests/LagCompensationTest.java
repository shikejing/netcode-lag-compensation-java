package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.engine.GameServer;
import com.github.shikejing.netcodelagcompensationjava.model.HitVerdict;
import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;
import com.github.shikejing.netcodelagcompensationjava.model.ShotQuery;
import com.github.shikejing.netcodelagcompensationjava.model.ShotResult;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lag-compensated hitscan: the world is rewound to the shooter's perceived
 * instant; requests outside the bounded window or in the future produce
 * distinguishable verdicts rather than silent accept/reject.
 */
class LagCompensationTest {

    private final ServerConfig config = ServerConfig.defaults();

    private InputCommand standStill(int client, int seq, long time) {
        return new InputCommand(client, seq, time, InputCommand.TICK_UNASSIGNED,
                Vec2.ZERO, false, 0.0);
    }

    @Test
    void hitIsResolvedAgainstTheRewoundWorld() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.registerClient(2);
        server.advanceTo(0);
        // Everyone stands still for a few ticks.
        for (int t = 1; t <= 5; t++) {
            long time = t * config.tickDurationMs();
            server.ingest(standStill(1, t, time));
            server.ingest(standStill(2, t, time));
            server.advanceTo(t);
        }
        // Spawns: client1 at (-40,0), client2 at (-32,0): client1 aims +X.
        long shotClientTime = 3 * config.tickDurationMs(); // maps to tick 3
        ShotQuery query = new ShotQuery(1, shotClientTime,
                new Vec2(-40.0, 0.0), new Vec2(1.0, 0.0));
        ShotResult result = server.processShot(1, query);

        assertEquals(HitVerdict.HIT, result.verdict(), result.reason());
        assertEquals(2, result.targetId());
        assertEquals(3L, result.rewoundTick());
        assertEquals(5L, result.currentTick());
        assertEquals(2L, result.rewindAgeTicks());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("rewinding"));
    }

    @Test
    void requestBeyondRewindWindowIsRejectedWithDistinctVerdict() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.registerClient(2);
        server.advanceTo(0);
        for (int t = 1; t <= config.maxRewindTicks() + 5; t++) {
            long time = t * config.tickDurationMs();
            server.ingest(standStill(1, t, time));
            server.ingest(standStill(2, t, time));
            server.advanceTo(t);
        }
        long tooOld = config.tickDurationMs(); // tick 1, far beyond window
        ShotResult result = server.processShot(1,
                new ShotQuery(1, tooOld, Vec2.ZERO, new Vec2(1, 0)));

        assertEquals(HitVerdict.REJECTED_BEYOND_WINDOW, result.verdict());
        assertTrue(result.rewindAgeTicks() > config.maxRewindTicks());
        assertTrue(result.reason().contains("rewind bound") || result.reason().contains("retained history"));
    }

    @Test
    void futureShotIsRejectedWithDistinctVerdict() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.registerClient(2);
        server.advanceTo(0);
        for (int t = 1; t <= 3; t++) {
            long time = t * config.tickDurationMs();
            server.ingest(standStill(1, t, time));
            server.ingest(standStill(2, t, time));
            server.advanceTo(t);
        }
        long futureTime = 100 * config.tickDurationMs();
        ShotResult result = server.processShot(1,
                new ShotQuery(1, futureTime, Vec2.ZERO, new Vec2(1, 0)));

        assertEquals(HitVerdict.REJECTED_FUTURE, result.verdict());
        assertTrue(result.requestedTick() >= result.currentTick());
        assertTrue(result.reason().contains("future"));
    }

    @Test
    void movingTargetIsHitWhereItWasAtShootersPerceivedTime() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.registerClient(2);
        server.advanceTo(0);
        // Target moves +X every tick; shooter still.
        for (int t = 1; t <= 6; t++) {
            long time = t * config.tickDurationMs();
            server.ingest(standStill(1, t, time));
            server.ingest(new InputCommand(2, t, time, InputCommand.TICK_UNASSIGNED,
                    new Vec2(1.0, 0.0), false, 0.0));
            server.advanceTo(t);
        }
        // At tick 2 target was at (-30, 0). Aim slightly past its *current* pos
        // and rewind: a ray at y=0 still passes through the rewounded body.
        long shotClientTime = 2 * config.tickDurationMs();
        ShotResult result = server.processShot(1,
                new ShotQuery(1, shotClientTime, new Vec2(-40, 0), new Vec2(1, 0)));
        assertEquals(HitVerdict.HIT, result.verdict(), result.reason());
        assertEquals(2L, result.rewoundTick());
        // Hit point matches the target's rewound position, not its current one.
        assertEquals(-30.0, result.hitPoint().x(), config.playerRadius() + config.hitRadius() + 0.01);
    }
}
