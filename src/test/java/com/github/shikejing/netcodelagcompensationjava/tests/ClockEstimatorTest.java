package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator;
import com.github.shikejing.netcodelagcompensationjava.time.ClockSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoothing of RTT/offset jitter and the monotone no-rewind guarantee when the
 * client clock is adjusted backwards.
 */
class ClockEstimatorTest {

    @Test
    void rttAndOffsetJitterAreSmoothed() {
        ClockEstimator clock = new ClockEstimator(0.2, 0.2);
        // Server runs 100 ms ahead; RTT jitters 40..160 ms.
        long[] rtts = {100, 40, 160, 50, 150, 60, 140};
        ClockSnapshot last = null;
        for (int i = 0; i < rtts.length; i++) {
            long send = 1000L + i * 100L;
            long recv = send + rtts[i];
            // server receive = client send + rtt/2 + 100 offset
            long serverRecv = send + rtts[i] / 2 + 100;
            last = clock.sample(send, serverRecv, recv);
        }
        assertTrue(last.rttSmoothedMs() >= 40.0 && last.rttSmoothedMs() <= 160.0,
                "smoothed rtt should stay inside observed range, was " + last.rttSmoothedMs());
        assertEquals(100.0, last.offsetSmoothedMs(), 30.0,
                "smoothed offset should converge near 100ms");
    }

    @Test
    void clockTurnedBackwardsNeverMovesServerEstimateBackwards() {
        ClockEstimator clock = new ClockEstimator(0.5, 0.5);
        clock.sample(1000, 1050, 1100);
        clock.sample(1100, 1150, 1200);
        long forward = clock.toServerTimeMs(1300);

        // Client clock is wound back by 500 ms.
        ClockSnapshot s = clock.sample(800, 1200, 900);
        long afterBackwards = clock.toServerTimeMs(900);

        assertTrue(afterBackwards >= forward,
                "estimated server time must not flow backwards: " + forward + " -> " + afterBackwards);
        assertTrue(s.adjustments() >= 1L, "a clock adjustment must be counted");
        // Monotonic across repeated calls too.
        assertEquals(afterBackwards, clock.toServerTimeMs(900));
    }

    @Test
    void increasingClientTimeProducesNondecreasingServerTime() {
        ClockEstimator clock = new ClockEstimator(0.3, 0.3);
        clock.sample(0, 25, 50);
        long prev = Long.MIN_VALUE;
        ClockSnapshot last = null;
        for (long t = 0; t <= 2000; t += 37) {
            last = clock.snapshot(t);
            assertTrue(last.serverTimeEstimateMs() >= prev);
            prev = last.serverTimeEstimateMs();
        }
        assertFalse(last.clamped(), "the final forward step must not require clamping");
    }
}
