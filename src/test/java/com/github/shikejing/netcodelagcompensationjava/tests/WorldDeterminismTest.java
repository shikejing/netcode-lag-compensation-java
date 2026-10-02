package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;
import com.github.shikejing.netcodelagcompensationjava.simulation.DeterministicRng;
import com.github.shikejing.netcodelagcompensationjava.simulation.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Frame-for-frame determinism with a fixed seed and identical inputs, including
 * across restarts reconstructed from saved RNG state, and repeated frames.
 */
class WorldDeterminismTest {

    private final ServerConfig config = ServerConfig.defaults();

    private List<InputCommand> inputsForTick(long tick) {
        long time = (tick - 1) * config.tickDurationMs();
        return List.of(
                new InputCommand(1, (int) tick, time, tick,
                        new Vec2(1.0, 0.3), tick % 3 == 0, tick * 0.05),
                new InputCommand(2, (int) tick, time, tick,
                        new Vec2(-0.5, 0.8), false, -tick * 0.03));
    }

    private List<WorldSnapshot> runSeries(long seed, int fromTick) {
        ServerConfig cfg = withSeed(seed);
        World world = new World(cfg);
        WorldSnapshot s = world.initialSnapshot(fromTick,
                fromTick * cfg.tickDurationMs(), List.of(1, 2));
        List<WorldSnapshot> out = new ArrayList<>();
        out.add(s);
        for (int tick = 1; tick <= 20; tick++) {
            s = world.step(s, inputsForTick(tick));
            out.add(s);
        }
        return out;
    }

    private ServerConfig withSeed(long seed) {
        ServerConfig d = ServerConfig.defaults();
        return new ServerConfig(d.tickDurationMs(), d.interpolationDelayTicks(),
                d.snapshotRetainTicks(), d.maxRewindTicks(), d.maxFutureInputTicks(),
                d.maxInputQueuePerClient(), d.arenaHalfSize(), d.playerRadius(),
                d.hitRadius(), d.fireDamage(), seed);
    }

    @Test
    void sameSeedSameInputsProduceIdenticalFrameSequence() {
        List<WorldSnapshot> a = runSeries(42L, 0);
        List<WorldSnapshot> b = runSeries(42L, 0);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertSnapshotEquals(a.get(i), b.get(i));
        }
    }

    @Test
    void differentSeedsDivergeOnRandomShots() {
        List<WorldSnapshot> a = runSeries(1L, 0);
        List<WorldSnapshot> b = runSeries(2L, 0);
        boolean anyEventDifference = false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).events().equals(b.get(i).events())
                    || a.get(i).rngState() != b.get(i).rngState()) {
                anyEventDifference = true;
            }
        }
        assertNotEquals(a.get(a.size() - 1).rngState(),
                new DeterministicRng(config.rngSeed()).state(),
                "rng must advance as shots fire");
        assertEquals(true, anyEventDifference, "different seeds must yield different RNG stream");
    }

    @Test
    void restartFromSavedSnapshotContinuesWithoutJump() {
        List<WorldSnapshot> full = runSeries(7L, 0);
        WorldSnapshot saved = full.get(10);

        // Rebuild mid-world purely from persisted state and continue.
        World world = new World(withSeed(7L));
        WorldSnapshot s = new WorldSnapshot(saved.tick(), saved.serverTimeMs(),
                saved.players(), saved.rngState(), saved.events());
        for (int tick = 11; tick <= 20; tick++) {
            s = world.step(s, inputsForTick(tick));
            assertSnapshotEquals(full.get(tick), s);
        }
    }

    static void assertSnapshotEquals(WorldSnapshot a, WorldSnapshot b) {
        assertEquals(a.tick(), b.tick());
        assertEquals(a.serverTimeMs(), b.serverTimeMs());
        assertEquals(a.rngState(), b.rngState());
        assertEquals(a.events(), b.events());
        assertEquals(a.players().keySet(), b.players().keySet());
        for (var e : a.players().entrySet()) {
            var pa = e.getValue();
            var pb = b.players().get(e.getKey());
            assertEquals(pa.position().x(), pb.position().x(), 1e-12);
            assertEquals(pa.position().y(), pb.position().y(), 1e-12);
            assertEquals(pa.aimAngle(), pb.aimAngle(), 1e-12);
            assertEquals(pa.health(), pb.health());
            assertEquals(pa.lastProcessedSequence(), pb.lastProcessedSequence());
        }
    }
}
