package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.NetcodeProperties;
import com.github.shikejing.netcodelagcompensationjava.engine.ServerEngine;
import com.github.shikejing.netcodelagcompensationjava.input.InputOutcome;
import com.github.shikejing.netcodelagcompensationjava.input.InputQueue;
import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 输入乱序、迟到、重复：
 *  - 重复 sequence 只应用一次；
 *  - 乱序到达不影响最终应用顺序；
 *  - 迟到输入不改写已发布快照；
 *  - 所有判定可计数、可追溯。
 */
class InputOrderingTest {

    private static final int TICK_RATE = 60;
    private static final long TICK_NANOS = 1_000_000_000L / TICK_RATE;

    private ServerEngine engine() {
        NetcodeProperties p = new NetcodeProperties();
        p.setTickRate(TICK_RATE);
        p.setFixedSeed(1L);
        return new ServerEngine(p);
    }

    private PlayerInput input(long seq, long tick, int mx, int my, boolean fire) {
        return new PlayerInput(seq, tick * TICK_NANOS, mx, my, fire);
    }

    @Test
    void duplicateInputAppliedOnlyOnce() {
        ServerEngine e = engine();
        e.addPlayer("a");
        long t1 = TICK_NANOS;
        InputQueue.InputDecision d1 = e.submitInput("a", input(1, 1, 1, 0, false), t1);
        InputQueue.InputDecision d2 = e.submitInput("a", input(1, 1, 1, 0, false), t1);
        assertEquals(InputOutcome.BUFFERED_OUT_OF_ORDER, d1.outcome());
        assertEquals(InputOutcome.DUPLICATE, d2.outcome());

        e.tick(); // tick 1
        var snap = e.latestSnapshot().orElseThrow();
        double xAfterOne = snap.entity("a").x();
        // 再来一个重复（应用后到达）
        InputQueue.InputDecision d3 = e.submitInput("a", input(1, 1, 1, 0, false), t1);
        assertEquals(InputOutcome.DUPLICATE, d3.outcome());
        e.tick(); // tick 2：无新输入，位置不应因重复输入改变
        var snap2 = e.latestSnapshot().orElseThrow();
        assertEquals(xAfterOne, snap2.entity("a").x(), "duplicate input must not move player again");
        assertEquals(2, e.queueOf("a").duplicateCount());
    }

    @Test
    void outOfOrderInputsAppliedInSequenceOrder() {
        ServerEngine e = engine();
        e.addPlayer("a");
        // 先到 seq 3，再到 seq 1，再到 seq 2
        e.submitInput("a", input(3, 3, 0, 1, false), 3 * TICK_NANOS);
        e.submitInput("a", input(1, 1, 1, 0, false), TICK_NANOS);
        e.submitInput("a", input(2, 2, 1, 0, false), 2 * TICK_NANOS);
        e.tick(); // tick 1：应用 seq 1（向右）
        assertEquals(22.0d, e.latestSnapshot().orElseThrow().entity("a").x(), 1e-9);
        e.tick(); // tick 2：应用 seq 2（向右）
        assertEquals(24.0d, e.latestSnapshot().orElseThrow().entity("a").x(), 1e-9);
        e.tick(); // tick 3：应用 seq 3（向下）
        assertEquals(24.0d, e.latestSnapshot().orElseThrow().entity("a").x(), 1e-9);
        assertEquals(22.0d, e.latestSnapshot().orElseThrow().entity("a").y(), 1e-9);
        assertEquals(2, e.queueOf("a").outOfOrderCount());
    }

    @Test
    void lateInputDoesNotRewritePublishedState() {
        ServerEngine e = engine();
        e.addPlayer("a");
        e.submitInput("a", input(1, 1, 1, 0, false), TICK_NANOS);
        e.tick(); // tick 1 已发布
        double publishedX = e.latestSnapshot().orElseThrow().entity("a").x();
        // 迟到输入：映射到 tick 0（早于已发布的 tick 1）
        InputQueue.InputDecision late = e.submitInput("a", input(2, 0, 5, 0, false), 0L);
        // seq 2 > lastAppliedSeq(1)，会先缓冲；tick 2 判定其映射 tick 0 < 2 -> LATE
        e.tick(); // tick 2
        var snap = e.latestSnapshot().orElseThrow();
        assertEquals(publishedX, snap.entity("a").x(), 1e-9, "late input must not rewrite published state");
        assertTrue(e.queueOf("a").lateCount() >= 1, "late input should be counted");
        // 迟到记录可追溯
        assertFalse(e.queueOf("a").lateRecords().isEmpty());
    }

    @Test
    void slowClientDoesNotBlockOthers() {
        ServerEngine e = engine();
        e.addPlayer("fast");
        e.addPlayer("slow");
        // fast 每 tick 都有输入；slow 只有第 1 tick 有输入
        for (long t = 1; t <= 10; t++) {
            e.submitInput("fast", input(t, t, 1, 0, false), t * TICK_NANOS);
            if (t == 1) e.submitInput("slow", input(1, 1, 0, 1, false), TICK_NANOS);
            e.tick();
        }
        var snap = e.latestSnapshot().orElseThrow();
        // fast 持续推进
        assertEquals(20.0d + 10 * 2.0d, snap.entity("fast").x(), 1e-9);
        // slow 在出生点 (40,20)，只向下移动一次，之后保持（不阻塞 fast）
        assertEquals(22.0d, snap.entity("slow").y(), 1e-9);
        assertEquals(40.0d, snap.entity("slow").x(), 1e-9);
    }
}
