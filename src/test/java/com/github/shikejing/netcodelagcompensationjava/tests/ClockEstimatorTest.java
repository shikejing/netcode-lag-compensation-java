package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时钟估计：偏移与 RTT 抖动被平滑；时钟回拨/跳变时回放时间轴不倒流、不跳变。
 */
class ClockEstimatorTest {

    private static final int TICK_RATE = 60;
    private static final long TN = 1_000_000_000L / TICK_RATE;

    @Test
    void offsetConvergesAndJitterIsSmoothed() {
        ClockEstimator c = new ClockEstimator(0.2d, 250_000_000L);
        long trueOffset = 40_000_000L; // 40ms
        // 带抖动的样本
        long[] jitters = {5_000_000L, -3_000_000L, 8_000_000L, -6_000_000L, 2_000_000L};
        for (int i = 0; i < 50; i++) {
            long j = jitters[i % jitters.length];
            long clientTime = i * TN;
            long recv = clientTime + trueOffset + j;
            c.addSample(clientTime, recv);
        }
        ClockEstimator.ClockEstimate est = c.estimate(100L, TICK_RATE);
        // 偏移收敛到 trueOffset 附近
        assertEquals(trueOffset, est.offsetNanos(), 8_000_000L, "offset should converge near true offset");
        // 抖动被平滑（小于最大抖动 8ms）
        assertTrue(est.jitterNanos() <= 8_000_000L, "jitter should be smoothed: " + est.jitterNanos());
        // RTT ≈ 2 * 单向偏移
        assertEquals(2 * trueOffset, est.rttNanos(), 16_000_000L);
    }

    @Test
    void clockRollbackDoesNotReverseMappedTick() {
        ClockEstimator c = new ClockEstimator(0.5d, 250_000_000L);
        // 正常推进到 tick 50
        for (int i = 0; i < 50; i++) {
            long clientTime = i * TN;
            c.addSample(clientTime, clientTime + 10_000_000L);
        }
        long mapped50 = c.mapToServerTick(50 * TN, 60L, TICK_RATE);
        // 客户端时钟回拨：clientTime 倒回 30*tick
        c.addSample(30 * TN, 30 * TN + 10_000_000L);
        long mappedAfterRollback = c.mapToServerTick(30 * TN, 60L, TICK_RATE);
        assertTrue(mappedAfterRollback >= mapped50,
                "mapped tick must not decrease on clock rollback: " + mapped50 + " -> " + mappedAfterRollback);
    }

    @Test
    void clockJumpForwardIsClampedToCurrentTick() {
        ClockEstimator c = new ClockEstimator(0.5d, 250_000_000L);
        for (int i = 0; i < 10; i++) {
            c.addSample(i * TN, i * TN);
        }
        // 客户端时钟突然跳到 10 秒后（巨大前跳）
        long mapped = c.mapToServerTick(10_000_000_000L, 20L, TICK_RATE);
        assertTrue(mapped <= 20L, "mapped tick must be clamped to current tick, was " + mapped);
        assertTrue(mapped >= 0L);
    }

    @Test
    void singleSampleJumpIsLimited() {
        ClockEstimator c = new ClockEstimator(0.5d, 100_000_000L); // 100ms 上限
        c.addSample(0L, 0L);
        // 偏移样本突然跳到 500ms（超过 100ms 上限）
        c.addSample(TN, TN + 500_000_000L);
        ClockEstimator.ClockEstimate est = c.estimate(10L, TICK_RATE);
        // 偏移不应一次跳满 500ms，而应被限制在 100ms 附近
        assertTrue(est.offsetNanos() <= 120_000_000L,
                "single sample jump should be limited, offset=" + est.offsetNanos());
    }
}
