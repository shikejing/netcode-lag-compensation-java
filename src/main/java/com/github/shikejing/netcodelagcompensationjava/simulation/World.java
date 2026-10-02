package com.github.shikejing.netcodelagcompensationjava.simulation;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;
import com.github.shikejing.netcodelagcompensationjava.model.PlayerState;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure deterministic fixed-step simulation. Advancing from tick N with a given
 * input set always produces the identical tick N+1 snapshot, independent of
 * wall-clock timing, thread scheduling or process restarts.
 *
 * <p>Movement model: the authoritative server moves players by the client-provided
 * displacement vector, whose magnitude is clamped to {@link #MAX_MOVE_PER_TICK}
 * and whose result is clamped to the arena. Dead players (health 0) never move.
 *
 * <p>Hit model: when a fire input is applied at tick T, the world is rewound to
 * the shooter's perceived instant — the authoritative tick T-1 snapshot — and
 * the hitscan is traced from there with the shooter's tick-T aim. Damage is then
 * carried into the tick T state. The weapon draws a tiny deterministic spread
 * from {@link DeterministicRng}, whose state is embedded in every snapshot, so
 * identical seeds and inputs reproduce every frame, shot and RNG draw.
 */
public final class World {

    /** Authoritative anti-cheat clamp on per-tick displacement magnitude. */
    public static final double MAX_MOVE_PER_TICK = 2.0;

    /** Deterministic weapon spread in radians (half-width, uniform via RNG). */
    public static final double WEAPON_SPREAD_RAD = 0.01;

    private final ServerConfig config;

    public World(ServerConfig config) {
        this.config = config;
    }

    public WorldSnapshot initialSnapshot(long tick0, long serverTime0Ms, Collection<Integer> clientIds) {
        Map<Integer, PlayerState> players = new HashMap<>();
        List<Integer> ordered = clientIds.stream().sorted().toList();
        for (int i = 0; i < ordered.size(); i++) {
            int id = ordered.get(i);
            // Deterministic spawn line, 8 units apart.
            Vec2 spawn = new Vec2(-40.0 + 8.0 * i, 0.0);
            players.put(id, new PlayerState(id, spawn, 0.0, 100, -1L));
        }
        long rngState = DeterministicRng.fromState(config.rngSeed()).state();
        return new WorldSnapshot(tick0, serverTime0Ms, players, rngState, List.of());
    }

    public WorldSnapshot step(WorldSnapshot previous, Collection<InputCommand> inputs) {
        long newTick = previous.tick() + 1;
        long newTime = previous.serverTimeMs() + config.tickDurationMs();

        // Deterministic per-client input selection: newest (highest sequence) wins
        // if several commands quantize onto the same tick.
        Map<Integer, InputCommand> byClient = new HashMap<>();
        for (InputCommand cmd : inputs) {
            InputCommand existing = byClient.get(cmd.clientId());
            if (existing == null || cmd.sequence() > existing.sequence()) {
                byClient.put(cmd.clientId(), cmd);
            }
        }

        Map<Integer, PlayerState> moved = new HashMap<>(previous.players());
        for (Map.Entry<Integer, PlayerState> e : previous.players().entrySet()) {
            PlayerState p = e.getValue();
            InputCommand cmd = byClient.get(p.clientId());
            if (cmd == null) {
                continue;
            }
            long seq = Math.max(p.lastProcessedSequence(), cmd.sequence());
            double aim = p.aimAngle();
            Vec2 pos = p.position();
            if (p.health() > 0) {
                Vec2 displacement = clampMagnitude(cmd.move(), MAX_MOVE_PER_TICK);
                pos = clampToArena(p.position().add(displacement));
                aim = cmd.aimAngle();
            }
            moved.put(p.clientId(), new PlayerState(p.clientId(), pos, aim, p.health(), seq));
        }

        DeterministicRng rng = DeterministicRng.fromState(previous.rngState());
        List<String> events = new ArrayList<>();

        // Fire in deterministic client order; each shot rewinds bodies to the
        // shooter's perceived moment = previous tick snapshot.
        List<Integer> shooters = byClient.values().stream()
                .filter(InputCommand::fire)
                .map(InputCommand::clientId)
                .sorted()
                .toList();

        for (int shooterId : shooters) {
            PlayerState shooterNow = moved.get(shooterId);
            if (shooterNow == null || shooterNow.health() <= 0) {
                continue;
            }
            PlayerState shooterRewound = previous.player(shooterId);
            Vec2 origin = shooterRewound != null ? shooterRewound.position() : shooterNow.position();
            double spread = (rng.nextUnit() - 0.5) * 2.0 * WEAPON_SPREAD_RAD;
            double angle = shooterNow.aimAngle() + spread;
            Vec2 dir = new Vec2(Math.cos(angle), Math.sin(angle)).normalized();

            TraceResult trace = traceHitscan(previous, shooterId, origin, dir,
                    config.playerRadius() + config.hitRadius());
            if (trace.targetId() < 0) {
                events.add("TICK " + newTick + " FIRE shooter=" + shooterId + " MISS");
                continue;
            }
            int targetId = trace.targetId();
            PlayerState target = moved.get(targetId);
            int newHealth = Math.max(0, target.health() - (int) Math.round(config.fireDamage()));
            moved.put(targetId, new PlayerState(targetId, target.position(), target.aimAngle(),
                    newHealth, target.lastProcessedSequence()));
            events.add("TICK " + newTick + " FIRE shooter=" + shooterId
                    + " HIT target=" + targetId + " damage=" + Math.round(config.fireDamage())
                    + " health=" + newHealth + " rewindFromTick=" + newTick
                    + " rewindToTick=" + previous.tick());
        }

        return new WorldSnapshot(newTick, newTime, moved, rng.state(), events);
    }

    private Vec2 clampMagnitude(Vec2 v, double max) {
        double len = v.length();
        if (len <= max) {
            return v;
        }
        return v.scale(max / len);
    }

    private Vec2 clampToArena(Vec2 p) {
        double half = config.arenaHalfSize();
        double x = Math.max(-half, Math.min(half, p.x()));
        double y = Math.max(-half, Math.min(half, p.y()));
        return new Vec2(x, y);
    }

    /** Result of a ray test; {@code targetId == -1} means a clean miss. */
    public record TraceResult(int targetId, Vec2 hitPoint) {
        public static final TraceResult MISS = new TraceResult(-1, Vec2.ZERO);
    }

    /**
     * Nearest-alive body intersecting the forward ray (circle vs. segment-ray).
     * Visible for the lag compensator so the exact same math is used on a
     * rewound snapshot.
     */
    public static TraceResult traceHitscan(WorldSnapshot world, int shooterId,
                                           Vec2 origin, Vec2 dir, double effectiveRadius) {
        Vec2 d = dir.normalized();
        int bestId = -1;
        double bestT = Double.POSITIVE_INFINITY;
        Vec2 bestPoint = Vec2.ZERO;

        for (PlayerState p : world.players().values()) {
            if (p.clientId() == shooterId || p.health() <= 0) {
                continue;
            }
            Vec2 to = p.position().subtract(origin);
            double along = to.dot(d);
            if (along < 0.0) {
                continue;
            }
            double perpSq = Math.max(0.0, to.lengthSquared() - along * along);
            double r2 = effectiveRadius * effectiveRadius;
            if (perpSq > r2) {
                continue;
            }
            double t = along - Math.sqrt(r2 - perpSq);
            if (t < bestT) {
                bestT = t;
                bestId = p.clientId();
                bestPoint = origin.add(d.scale(t));
            }
        }
        return bestId < 0 ? TraceResult.MISS : new TraceResult(bestId, bestPoint);
    }
}
