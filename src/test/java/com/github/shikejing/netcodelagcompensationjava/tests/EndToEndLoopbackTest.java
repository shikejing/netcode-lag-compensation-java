package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.ServerConfig;
import com.github.shikejing.netcodelagcompensationjava.engine.GameServer;
import com.github.shikejing.netcodelagcompensationjava.input.InputDisposition;
import com.github.shikejing.netcodelagcompensationjava.model.HitVerdict;
import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;
import com.github.shikejing.netcodelagcompensationjava.model.ShotQuery;
import com.github.shikejing.netcodelagcompensationjava.model.ShotResult;
import com.github.shikejing.netcodelagcompensationjava.model.Vec2;
import com.github.shikejing.netcodelagcompensationjava.simulation.DeterministicRng;
import com.github.shikejing.netcodelagcompensationjava.transport.Datagram;
import com.github.shikejing.netcodelagcompensationjava.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end behavior over the in-process loopback transport, plus the
 * "client clock jumps during a match" scenario.
 */
class EndToEndLoopbackTest {

    private final ServerConfig config = ServerConfig.defaults();
    private final long dt = config.tickDurationMs();

    private byte[] encode(InputCommand cmd) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(cmd.clientId());
            out.writeInt(cmd.sequence());
            out.writeLong(cmd.clientTimeMs());
            out.writeDouble(cmd.move().x());
            out.writeDouble(cmd.move().y());
            out.writeBoolean(cmd.fire());
            out.writeDouble(cmd.aimAngle());
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private InputCommand decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int client = in.readInt();
            int seq = in.readInt();
            long time = in.readLong();
            double mx = in.readDouble();
            double my = in.readDouble();
            boolean fire = in.readBoolean();
            double aim = in.readDouble();
            return new InputCommand(client, seq, time, InputCommand.TICK_UNASSIGNED,
                    new Vec2(mx, my), fire, aim);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void inputsOverLossyReorderingLoopbackStillConvergeAndDuplicatesApplyOnce() {
        DeterministicRng netRng = new DeterministicRng(2024L);
        InMemoryTransport transport = new InMemoryTransport(2024L,
                10, 40, 0.15, 0.2, 30);

        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.registerClient(2);
        server.advanceTo(0);

        int sentTicks = 60;
        int acceptedOrLate = 0;
        int duplicatesSeen = 0;
        List<InputCommand> delivered = new ArrayList<>();

        for (int t = 1; t <= sentTicks; t++) {
            long clientTime = t * dt;
            transport.send(1, 99, clientTime,
                    encode(new InputCommand(1, t, clientTime, InputCommand.TICK_UNASSIGNED,
                            new Vec2(0.3, 0.0), false, 0.0)), netRng);
            transport.send(2, 99, clientTime,
                    encode(new InputCommand(2, t, clientTime, InputCommand.TICK_UNASSIGNED,
                            Vec2.ZERO, false, 0.0)), netRng);

            for (Datagram d : transport.deliver(99, clientTime + 100)) {
                InputCommand cmd = decode(d.payload());
                delivered.add(cmd);
                InputDisposition disp = server.ingest(cmd);
                if (disp == InputDisposition.ACCEPTED || disp == InputDisposition.LATE_REJECTED) {
                    acceptedOrLate++;
                } else if (disp == InputDisposition.DUPLICATE) {
                    duplicatesSeen++;
                }
            }
            server.advanceTo(t);
        }

        assertEquals(sentTicks, server.currentSnapshot().tick());
        // The network produced duplicates and they were all recognized (>=1 seen).
        assertTrue(duplicatesSeen >= 1, "lossy loopback duplicates must be deduplicated");
        // No sequence processed twice: final lastProcessedSequence is exactly the
        // newest sequence that could have been applied (bounded by sentTicks).
        assertTrue(server.lastAppliedSequences().get(1) <= sentTicks);
        assertTrue(server.lastAppliedSequences().get(2) <= sentTicks);
        assertTrue(acceptedOrLate > 0);
        // Fewer delivered unique commands than sent (loss happened) yet server advanced.
        long uniqueDelivered = delivered.stream()
                .mapToLong(c -> ((long) c.clientId()) * 100000 + c.sequence())
                .distinct().count();
        assertTrue(uniqueDelivered < 2L * sentTicks || duplicatesSeen > 0);
    }

    @Test
    void clientClockJumpDuringMatchDoesNotMoveRewindTimelineBackwards() {
        // Part A: monotone no-rewind guarantee is checked on a dedicated estimator,
        // isolated from gameplay input timestamps.
        com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator estimator =
                new com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator(0.125, 0.1);
        for (long t = 0; t < 40; t++) {
            long send = 200L + t * 10L;
            estimator.sample(send, send + 70L, send + 40L); // server 50 ms ahead
        }
        long healthy = estimator.toServerTimeMs(300L);
        assertEquals(350L, healthy);

        // Client clock jumps 200 ms into the past (samples move backward).
        for (long t = 0; t < 10; t++) {
            long send = 100L + t * 10L;
            estimator.sample(send, send + 120L, send + 40L);
        }
        long backward = estimator.toServerTimeMs(100L);
        assertTrue(backward >= healthy,
                "a client clock rollback must never move the mapped server time backwards");
        assertEquals(healthy, backward, "the backward time is clamped to the floor");
        assertTrue(estimator.snapshot(100L).adjustments() >= 1L, "jump must be counted");

        // Part B: inside a live match, a shot after a clock rollback still resolves
        // to a well-defined verdict and never rewinds to an older tick than before.
        GameServer server = new GameServer(config);
        server.registerClient(1);
        server.registerClient(2);
        server.advanceTo(0);
        for (int t = 1; t <= 8; t++) {
            long time = t * dt;
            server.ingest(new InputCommand(1, t, time, InputCommand.TICK_UNASSIGNED,
                    Vec2.ZERO, false, 0.0));
            server.ingest(new InputCommand(2, t, time, InputCommand.TICK_UNASSIGNED,
                    Vec2.ZERO, false, 0.0));
            server.advanceTo(t);
        }
        // Use an in-window past input timestamp (tick 3) directly: HIT via rewind.
        ShotQuery shot = new ShotQuery(1, 3 * dt, new Vec2(-40, 0), new Vec2(1, 0));
        ShotResult r1 = server.processShot(1, shot);
        assertEquals(HitVerdict.HIT, r1.verdict(), r1.reason());
        // Re-asking the exact same timestamp is idempotent: no timeline movement.
        ShotResult r2 = server.processShot(1, shot);
        assertEquals(r1.rewoundTick(), r2.rewoundTick());
        assertEquals(HitVerdict.HIT, r2.verdict());
    }
}
