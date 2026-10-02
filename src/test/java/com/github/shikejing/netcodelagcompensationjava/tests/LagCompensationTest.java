package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.NetcodeProperties;
import com.github.shikejing.netcodelagcompensationjava.engine.ServerEngine;
import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.HitRecord;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.HitStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 延迟补偿命中判定：按射手视角回退到其输入时刻；
 * 回退窗口有上界，超出上界返回可区分的 TOO_LATE（不静默接受/拒绝）。
 */
class LagCompensationTest {

    private static final int TICK_RATE = 60;
    private static final long TN = 1_000_000_000L / TICK_RATE;

    private NetcodeProperties props(int maxRewind) {
        NetcodeProperties p = new NetcodeProperties();
        p.setTickRate(TICK_RATE);
        p.setFixedSeed(7L);
        p.setMaxRewindTicks(maxRewind);
        return p;
    }

    private PlayerInput input(long seq, long tick, int mx, int my, boolean fire) {
        return new PlayerInput(seq, tick * TN, mx, my, fire);
    }

    @Test
    void hitWhenTargetOnLine() {
        ServerEngine e = new ServerEngine(props(12));
        e.addPlayer("shooter");
        e.addPlayer("target");
        // shooter (20,20) 朝 +x，target (40,20) 在射线上
        e.submitInput("shooter", input(1, 1, 0, 0, true), TN);
        e.submitInput("target", input(1, 1, 0, 0, false), TN);
        e.tick();
        HitRecord hit = e.hitRecords().get(0);
        assertEquals(HitStatus.HIT, hit.status());
        assertEquals("target", hit.victimId());
        assertEquals(1L, hit.rewindTick());
    }

    @Test
    void missWhenTargetNotOnLine() {
        ServerEngine e = new ServerEngine(props(12));
        e.addPlayer("shooter");
        e.addPlayer("target");
        // 先让 target 向下移动 20 tick 离开射线
        for (long t = 1; t <= 20; t++) {
            e.submitInput("shooter", input(t, t, 0, 0, false), t * TN);
            e.submitInput("target", input(t, t, 0, 1, false), t * TN);
            e.tick();
        }
        // shooter 朝 +x 开火，target 已在 (40,60)
        e.submitInput("shooter", input(21, 21, 0, 0, true), 21 * TN);
        e.tick();
        HitRecord hit = e.hitRecords().get(e.hitRecords().size() - 1);
        assertEquals(HitStatus.MISS, hit.status());
        assertNull(hit.victimId());
    }

    @Test
    void tooLateWhenRewindExceedsWindow() {
        ServerEngine e = new ServerEngine(props(5));
        e.addPlayer("shooter");
        e.addPlayer("target");
        for (long t = 1; t <= 20; t++) {
            e.submitInput("shooter", input(t, t, 0, 0, false), t * TN);
            e.submitInput("target", input(t, t, 0, 1, false), t * TN);
            e.tick();
        }
        // 开火输入映射到 tick 10（10 tick 前），超过窗口 5
        // 用平滑时钟：先提交 offset=0 的样本，再提交这个迟到开火
        e.submitInput("shooter", input(21, 10, 0, 0, true), 20 * TN);
        e.tick();
        HitRecord hit = e.hitRecords().get(e.hitRecords().size() - 1);
        assertEquals(HitStatus.TOO_LATE, hit.status());
        assertNull(hit.victimId());
        assertTrue(hit.reason().contains("max rewind window"));
    }

    @Test
    void hitUsesRewoundPositionNotCurrent() {
        // target 在 tick 1..5 位于射线上，tick 6 移开。
        // 射手在 tick 6 开火，输入映射到 tick 3（回退），应按 tick 3 位置判定 -> HIT。
        ServerEngine e = new ServerEngine(props(12));
        e.addPlayer("shooter");
        e.addPlayer("target");
        for (long t = 1; t <= 6; t++) {
            int ty = (t >= 6) ? 1 : 0;
            e.submitInput("shooter", input(t, t, 0, 0, t == 6), t * TN);
            e.submitInput("target", input(t, t, 0, ty, false), t * TN);
            e.tick();
        }
        // 迟到开火：clientTime 对应 tick 3，在 tick 7 到达 -> 回退到 tick 3
        e.submitInput("shooter", input(7, 3, 0, 0, true), 6 * TN);
        e.tick();
        HitRecord hit = e.hitRecords().get(e.hitRecords().size() - 1);
        assertEquals(HitStatus.HIT, hit.status());
        assertEquals(3L, hit.rewindTick());
    }

    @Test
    void duplicateFireNotEvaluatedTwice() {
        ServerEngine e = new ServerEngine(props(12));
        e.addPlayer("shooter");
        e.addPlayer("target");
        e.submitInput("shooter", input(1, 1, 0, 0, true), TN);
        e.submitInput("target", input(1, 1, 0, 0, false), TN);
        e.tick();
        int hitsAfterFirst = e.hitRecords().size();
        // 重复提交同一开火输入
        e.submitInput("shooter", input(1, 1, 0, 0, true), TN);
        e.tick();
        assertEquals(hitsAfterFirst, e.hitRecords().size(), "duplicate fire must not be evaluated twice");
    }
}
