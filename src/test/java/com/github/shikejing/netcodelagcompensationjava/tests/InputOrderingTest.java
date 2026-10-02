package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.engine.GameServer;
import com.github.shikejing.netcodelagcompensationjava.input.InputDisposition;
import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Out-of-order, late and duplicate input handling. Duplicates are never applied
 * twice; late inputs never rewrite an already published authoritative result;
 * every decision is explainable via the audit trail.
 */
class InputOrderingTest {

    private final ServerConfig config = ServerConfig.defaults();

    private InputCommand cmd(int seq, long time, double x) {
        return new InputCommand(1, seq, time, InputCommand.TICK_UNASSIGNED,
                new Vec2(x, 0.0), false, 0.0);
    }

    @Test
    void duplicateIsAppliedExactlyOnceAndAudited() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.advanceTo(0);

        long t = config.tickDurationMs(); // maps to target tick 1
        InputCommand c = cmd(100, t, 1.0);
        assertEquals(InputDisposition.ACCEPTED, server.ingest(c));
        assertEquals(InputDisposition.DUPLICATE, server.ingest(c));
        assertEquals(InputDisposition.DUPLICATE,
                server.ingest(new InputCommand(1, 100, t, InputCommand.TICK_UNASSIGNED,
                        new Vec2(9.0, 9.0), true, 3.0)));

        server.advanceTo(1);
        // The duplicate's payload (9.0,9.0 / fire) must not have been applied.
        WorldSnapshot s = server.currentSnapshot();
        assertEquals(-40.0 + 1.0, s.player(1).position().x(), 1e-12);
        assertEquals(100L, s.player(1).lastProcessedSequence());
        assertTrue(server.auditEntries().stream()
                .anyMatch(a -> a.disposition() == InputDisposition.DUPLICATE));
    }

    @Test
    void lateInputNeverRewritesPublishedTick() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.advanceTo(0);
        server.ingest(cmd(1, config.tickDurationMs(), 1.0));
        server.advanceTo(1);
        WorldSnapshot published = server.currentSnapshot();
        double publishedX = published.player(1).position().x();

        // A late, retransmitted input that quantizes onto already-published tick 1.
        InputDisposition late = server.ingest(cmd(999, config.tickDurationMs(), 5.0));
        assertEquals(InputDisposition.LATE_REJECTED, late);
        assertEquals(publishedX, server.history().at(1).player(1).position().x(), 1e-12);
        assertEquals(1L, server.currentSnapshot().tick());
        assertNotEquals(999L, server.currentSnapshot().player(1).lastProcessedSequence());

        var lateAudit = server.auditEntries().stream()
                .filter(a -> a.disposition() == InputDisposition.LATE_REJECTED)
                .findFirst().orElseThrow();
        assertTrue(lateAudit.detail().contains("not rewritten"),
                "the late decision must be explainable, was: " + lateAudit.detail());
    }

    @Test
    void outOfOrderArrivalIsReorderedByTargetTick() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.advanceTo(0);

        // Arrive in reverse time order: tick 3 first, then tick 2, then tick 1.
        // Input time t is applied on tick floor(t/dt), so tick N owns [N*dt, (N+1)*dt).
        server.ingest(cmd(3, 3 * config.tickDurationMs(), 3.0));
        server.ingest(cmd(2, 2 * config.tickDurationMs(), 2.0));
        server.ingest(cmd(1, config.tickDurationMs(), 1.0));

        server.advanceTo(1);
        assertEquals(-40.0 + 1.0, server.currentSnapshot().player(1).position().x(), 1e-12);
        server.advanceTo(2);
        assertEquals(-40.0 + 1.0 + 2.0, server.currentSnapshot().player(1).position().x(), 1e-12);
        server.advanceTo(3);
        // move (3,0) is authoritatively clamped to MAX_MOVE_PER_TICK (2.0).
        assertEquals(-40.0 + 1.0 + 2.0 + 2.0,
                server.currentSnapshot().player(1).position().x(), 1e-12);
    }

    @Test
    void inputTooFarInFutureIsRejectedWithDistinctReason() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.advanceTo(0);
        long farFutureTime = (config.maxFutureInputTicks() + 5L) * config.tickDurationMs();
        assertEquals(InputDisposition.TOO_FAR_FUTURE,
                server.ingest(cmd(7, farFutureTime, 1.0)));
        assertTrue(server.auditEntries().stream()
                .anyMatch(a -> a.disposition() == InputDisposition.TOO_FAR_FUTURE
                        && a.detail().contains("beyond future bound")));
    }

    @Test
    void everyAcceptedInputIsTraceableThroughAuditLog() {
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.advanceTo(0);
        server.ingest(cmd(1, config.tickDurationMs(), 1.0));
        server.ingest(cmd(1, config.tickDurationMs(), 1.0)); // duplicate
        server.advanceTo(1);

        var clientAudit = server.auditEntries().stream().toList();
        long accepted = clientAudit.stream().filter(a -> a.disposition() == InputDisposition.ACCEPTED).count();
        long duplicated = clientAudit.stream().filter(a -> a.disposition() == InputDisposition.DUPLICATE).count();
        assertEquals(1, accepted);
        assertEquals(1, duplicated);
    }
}
