package com.github.shikejing.netcodelagcompensationjava.engine;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.input.AuditLog;
import com.github.shikejing.netcodelagcompensationjava.input.InputAudit;
import com.github.shikejing.netcodelagcompensationjava.input.InputDisposition;
import com.github.shikejing.netcodelagcompensationjava.input.InputMailbox;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.LagCompensator;
import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;
import com.github.shikejing.netcodelagcompensationjava.model.ShotQuery;
import com.github.shikejing.netcodelagcompensationjava.model.ShotResult;
import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;
import com.github.shikejing.netcodelagcompensationjava.snapshot.SnapshotHistory;
import com.github.shikejing.netcodelagcompensationjava.simulation.World;
import com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Front door of the authoritative server.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li>Any client thread may call {@link #ingest} / clock-sample methods
 *       concurrently. Those only touch thread-safe structures (mailbox, per-client
 *       clock estimators, audit log) and never mutate a published snapshot.</li>
 *   <li>Fixed-step advancement is serialized on {@code advanceLock}: the world
 *       steps one tick at a time, draining that tick's complete input set, then
 *       atomically publishes one immutable {@link WorldSnapshot}. An external
 *       observer therefore only ever sees a self-consistent snapshot, never a
 *       half-applied input set.</li>
 *   <li>Backlogs are per-client and bounded, so a slow client cannot block the
 *       advancement driven by everyone else.</li>
 * </ul>
 *
 * <p>Time quantization: the client timestamp is mapped onto the server timeline
 * by that client's smoothed clock estimator, then assigned to the
 * authoritative tick covering that instant ({@code floor(t/dt)}). Tick 0 is
 * published at time 0 before inputs exist, so an input at time 0 is naturally
 * late; later instants target ticks 1, 2, ...
 */
public final class GameServer {

    private static final int AUDIT_CAPACITY = 4096;

    private final ServerConfig config;
    private final World world;
    private final SnapshotHistory history;
    private final InputMailbox mailbox;
    private final AuditLog auditLog;
    private final Map<Integer, ClockEstimator> clocks = new HashMap<>();
    private final SortedSet<Integer> registeredClients = new TreeSet<>();
    private final Object clocksLock = new Object();
    private final Object advanceLock = new Object();

    /** Null until the first advancement publishes tick 0 with the registered roster. */
    private WorldSnapshot current;

    public GameServer(ServerConfig config) {
        this(config, null);
    }

    public GameServer(ServerConfig config, ServerState restored) {
        this.config = config;
        this.world = new World(config);
        this.history = new SnapshotHistory(config.snapshotRetainTicks());
        this.mailbox = new InputMailbox(config.maxInputQueuePerClient());
        this.auditLog = new AuditLog(AUDIT_CAPACITY);
        if (restored != null && restored.latestSnapshot() != null) {
            this.current = restored.latestSnapshot();
            this.registeredClients.addAll(current.players().keySet());
            this.history.append(current);
        }
    }

    public void registerClient(int clientId) {
        mailbox.registerClient(clientId);
        synchronized (clocksLock) {
            clocks.computeIfAbsent(clientId, k -> new ClockEstimator(0.125, 0.1));
        }
        synchronized (advanceLock) {
            if (current != null) {
                throw new IllegalStateException(
                        "client " + clientId + " cannot join after the authoritative world has started "
                                + "(currentTick=" + current.tick() + "); register before the first advanceTo()");
            }
            registeredClients.add(clientId);
        }
    }

    public ClockEstimator clock(int clientId) {
        synchronized (clocksLock) {
            return clocks.get(clientId);
        }
    }

    public ClockEstimator sampleClock(int clientId, long clientSendMs,
                                      long serverReceiveMs, long clientReceiveMs) {
        ClockEstimator est;
        synchronized (clocksLock) {
            est = clocks.computeIfAbsent(clientId, k -> new ClockEstimator(0.125, 0.1));
        }
        est.sample(clientSendMs, serverReceiveMs, clientReceiveMs);
        return est;
    }

    public InputDisposition ingest(InputCommand command) {
        long tickNow;
        long serverTimeNow;
        synchronized (advanceLock) {
            if (current == null) {
                tickNow = -1L;
                serverTimeNow = 0L;
            } else {
                tickNow = current.tick();
                serverTimeNow = current.serverTimeMs();
            }
        }
        ClockEstimator est;
        synchronized (clocksLock) {
            est = clocks.get(command.clientId());
        }
        long estimatedServerMs =
                est == null ? command.clientTimeMs() : est.toServerTimeMs(command.clientTimeMs());
        long targetTick = Math.floorDiv(estimatedServerMs, config.tickDurationMs());

        InputDisposition disposition;
        // The input may target up to maxFutureInputTicks ticks ahead of the
        // newest published tick (inclusive).
        long futureBound = Math.max(0L, tickNow) + config.maxFutureInputTicks();
        if (targetTick > futureBound) {
            disposition = InputDisposition.TOO_FAR_FUTURE;
        } else if (tickNow < 0) {
            // World not started yet: hold the input for its target tick.
            disposition = mailbox.ingest(command, targetTick, -1L);
        } else {
            disposition = mailbox.ingest(command, targetTick, tickNow);
        }
        String detail = switch (disposition) {
            case ACCEPTED -> "queued for tick " + targetTick
                    + " (clientTime=" + command.clientTimeMs()
                    + " -> serverTime~" + estimatedServerMs + ")";
            case DUPLICATE -> "sequence " + command.sequence() + " already seen for client "
                    + command.clientId();
            case LATE_REJECTED -> "targets tick " + targetTick + " <= published tick " + tickNow
                    + "; published authoritative result is not rewritten";
            case TOO_FAR_FUTURE -> "targets tick " + targetTick + " beyond future bound "
                    + futureBound;
            case BACKLOG_FULL -> "client " + command.clientId() + " backlog full ("
                    + config.maxInputQueuePerClient() + "); input isolated, other clients unaffected";
        };
        auditLog.append(new InputAudit(serverTimeNow, Math.max(-1L, tickNow), command, targetTick,
                disposition, detail));
        return disposition;
    }

    /** Advance (including catching up) through {@code targetTick}; returns new snapshots. */
    public List<WorldSnapshot> advanceTo(long targetTick) {
        List<WorldSnapshot> produced = new ArrayList<>();
        synchronized (advanceLock) {
            if (current == null) {
                current = world.initialSnapshot(0L, 0L, registeredClients);
                history.append(current);
                produced.add(current);
            }
            while (current.tick() < targetTick) {
                long nextTick = current.tick() + 1;
                List<InputCommand> inputs = mailbox.drainTick(nextTick);
                WorldSnapshot next = world.step(current, inputs);
                history.append(next);
                current = next;
                produced.add(next);
            }
            // Any queued commands whose tick has now passed without being drained
            // (they were late relative to actual progression) are pruned.
            mailbox.dropOlderThan(current.tick());
        }
        return produced;
    }

    public WorldSnapshot currentSnapshot() {
        synchronized (advanceLock) {
            return current;
        }
    }

    public SnapshotHistory history() {
        return history;
    }

    public LagCompensator lagCompensator(int clientId) {
        ClockEstimator est;
        synchronized (clocksLock) {
            est = clocks.computeIfAbsent(clientId, k -> new ClockEstimator(0.125, 0.1));
        }
        return new LagCompensator(config, est);
    }

    public ShotResult processShot(int shooterId, ShotQuery query) {
        ClockEstimator est;
        synchronized (clocksLock) {
            est = clocks.computeIfAbsent(shooterId, k -> new ClockEstimator(0.125, 0.1));
        }
        return new LagCompensator(config, est).evaluate(query, history);
    }

    public List<InputAudit> auditEntries() {
        return auditLog.entries();
    }

    public ServerState exportState() {
        synchronized (advanceLock) {
            if (current == null) {
                return null;
            }
            return new ServerState(current.tick(), current.serverTimeMs(),
                    current.rngState(), current);
        }
    }

    public Map<Integer, Long> lastAppliedSequences() {
        synchronized (advanceLock) {
            Map<Integer, Long> out = new HashMap<>();
            if (current != null) {
                for (var e : current.players().entrySet()) {
                    out.put(e.getKey(), e.getValue().lastProcessedSequence());
                }
            }
            return out;
        }
    }
}
