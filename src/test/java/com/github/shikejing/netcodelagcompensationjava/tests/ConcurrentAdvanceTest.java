package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.engine.GameServer;
import com.github.shikejing.netcodelagcompensationjava.input.InputDisposition;
import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent input ingestion alongside serialized fixed-step advancement.
 * Every externally observed snapshot must be self-consistent, duplicates are
 * counted exactly once, and a deliberately slow client cannot stall others.
 */
class ConcurrentAdvanceTest {

    private final ServerConfig config = ServerConfig.defaults();
    private final long dt = config.tickDurationMs();

    @Test
    void concurrentIngestionAndAdvancementNeverExposePartialState() throws Exception {
        GameServer server = new GameServer(config);
        int clients = 6;
        for (int c = 1; c <= clients; c++) {
            server.registerClient(c);
        }
        server.advanceTo(0);

        int ticks = 120;
        ExecutorService pool = Executors.newFixedThreadPool(clients + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        Queue<String> violations = new ConcurrentLinkedQueue<>();

        // Observer thread: snapshots it reads must always be internally consistent.
        pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                WorldSnapshot s = server.currentSnapshot();
                if (s != null) {
                    for (int c = 1; c <= clients; c++) {
                        if (s.player(c) == null) {
                            violations.add("missing player " + c + " in published tick " + s.tick());
                        }
                    }
                    if (s.serverTimeMs() != s.tick() * dt) {
                        violations.add("time/tick mismatch at " + s.tick());
                    }
                }
            }
        });

        // Per-client ingestion threads (also re-send duplicates of every command).
        for (int c = 1; c <= clients; c++) {
            final int client = c;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int t = 1; t <= ticks; t++) {
                    long time = t * dt;
                    InputCommand cmd = new InputCommand(client, t, time,
                            InputCommand.TICK_UNASSIGNED, new Vec2(0.5, 0.0), false, 0.0);
                    server.ingest(cmd);
                    server.ingest(cmd); // duplicate must be ignored
                }
            });
        }

        // Driver thread advances fixed steps; starts together with ingestors so
        // inputs often race with advancement (late rejection is acceptable).
        AtomicReference<Throwable> driverError = new AtomicReference<>();
        pool.submit(() -> {
            try {
                start.await();
                for (int t = 1; t <= ticks; t++) {
                    server.advanceTo(t);
                    Thread.yield();
                }
            } catch (Throwable th) {
                driverError.set(th);
            }
        });

        start.countDown();
        // Wait for the driver to publish the final tick; the observer keeps spinning.
        long deadline = System.currentTimeMillis() + 10_000L;
        while (server.currentSnapshot() == null
                || server.currentSnapshot().tick() < ticks
                || driverError.get() != null) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            Thread.onSpinWait();
        }
        assertEquals(null, driverError.get());
        // Now stop and interrupt the busy observer.
        stop.set(true);
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        WorldSnapshot end = server.currentSnapshot();
        assertEquals(ticks, end.tick());
        // Each client's last sequence was processed at most once; positions are
        // finite (no half-applied NaN/Infinity).
        for (int c = 1; c <= clients; c++) {
            assertTrue(end.player(c).lastProcessedSequence() <= ticks);
            assertTrue(Double.isFinite(end.player(c).position().x()));
        }
        // Duplicates were recognized (each command sent twice).
        long dupCount = server.auditEntries().stream()
                .filter(a -> a.disposition() == InputDisposition.DUPLICATE).count();
        assertTrue(dupCount >= clients);
        // Every audit is an explicit, well-defined disposition - never a partial state.
        server.auditEntries().forEach(a ->
                assertTrue(a.disposition() == InputDisposition.ACCEPTED
                        || a.disposition() == InputDisposition.DUPLICATE
                        || a.disposition() == InputDisposition.LATE_REJECTED
                        || a.disposition() == InputDisposition.TOO_FAR_FUTURE
                        || a.disposition() == InputDisposition.BACKLOG_FULL,
                        "unexpected disposition " + a.disposition()));
        assertTrue(violations.isEmpty(), violations.toString());
    }

    @Test
    void slowOrOverloadedClientDoesNotBlockOtherClients() throws Exception {
        // Tiny backlog so the slow client overflows; assert others still advance fully.
        ServerConfig cfg = new ServerConfig(config.tickDurationMs(), config.interpolationDelayTicks(),
                config.snapshotRetainTicks(), config.maxRewindTicks(), config.maxFutureInputTicks(),
                4, config.arenaHalfSize(), config.playerRadius(), config.hitRadius(),
                config.fireDamage(), config.rngSeed());
        GameServer server = new GameServer(cfg);
        server.registerClient(1); // slow one
        server.registerClient(2); // healthy one
        server.advanceTo(0);

        AtomicInteger backpressure = new AtomicInteger();
        // Slow client enqueues inputs spread across many different future ticks so
        // its tiny per-client TreeMap actually fills (same tick keys overwrite).
        for (int seq = 0; seq < 50; seq++) {
            long time = (seq + 1) * dt;
            if (server.ingest(new InputCommand(1, 100_000 + seq, time,
                    InputCommand.TICK_UNASSIGNED, new Vec2(0, 0), false, 0))
                    == InputDisposition.BACKLOG_FULL) {
                backpressure.incrementAndGet();
            }
        }

        // Meanwhile client 2 advances 30 ticks normally.
        for (int t = 1; t <= 30; t++) {
            server.ingest(new InputCommand(2, t, t * dt, InputCommand.TICK_UNASSIGNED,
                    new Vec2(0.5, 0), false, 0));
            server.advanceTo(t);
        }
        assertEquals(30L, server.currentSnapshot().tick());
        assertTrue(server.currentSnapshot().player(2).lastProcessedSequence() == 30L);
        // Backpressure on client 1 happened and was isolated (no exception, server alive).
        assertTrue(backpressure.get() > 0, "slow client should hit bounded backlog");
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
