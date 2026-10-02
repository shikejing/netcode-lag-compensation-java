package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.NetcodeProperties;
import com.github.shikejing.netcodelagcompensationjava.engine.ServerEngine;
import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回放一致性：快照能回放到任意历史时刻；
 * 剪枝后重放的结果必须与直接在该时刻推进得到的结果一致。
 */
class SnapshotReplayTest {

    private static final int TICK_RATE = 60;
    private static final long TICK_NANOS = 1_000_000_000L / TICK_RATE;

    private NetcodeProperties props(int history) {
        NetcodeProperties p = new NetcodeProperties();
        p.setTickRate(TICK_RATE);
        p.setFixedSeed(42L);
        p.setSnapshotHistoryTicks(history);
        return p;
    }

    private void script(ServerEngine e) {
        e.addPlayer("a");
        e.addPlayer("b");
        for (long t = 1; t <= 20; t++) {
            long clientTime = t * TICK_NANOS;
            long recv = clientTime; // 零时钟偏移：输入映射到自身 tick
            int mx = t <= 10 ? 1 : 0;
            int my = t <= 10 ? 0 : 1;
            e.submitInput("a", new PlayerInput(t, clientTime, mx, my, t == 15), recv);
            e.submitInput("b", new PlayerInput(t, clientTime, 0, 0, t == 18), recv);
            e.tick();
        }
    }

    @Test
    void replayMatchesDirectStepping_withoutPruning() {
        ServerEngine e = new ServerEngine(props(256));
        script(e);
        for (long t = 0; t <= 20; t++) {
            WorldSnapshot stored = e.snapshotAt(t).orElseThrow();
            assertEquals(t, stored.tick());
            // 直接取到的快照与回放结果必须是同一个对象（未剪枝时）
            assertSame(stored, e.rewindTo(t));
        }
    }

    @Test
    void replayMatchesDirectStepping_afterPruning() {
        ServerEngine pruned = new ServerEngine(props(8));
        ServerEngine full = new ServerEngine(props(256));
        script(pruned);
        script(full);

        assertEquals(20, pruned.currentTick());
        // 容量 8：运行 20 tick 后最旧快照应在 12（0..11 已被剪枝）
        assertEquals(12, pruned.currentTick() - 8);

        long oldest = pruned.oldestSnapshotTick();
        assertTrue(oldest >= 12, "oldest after pruning should be >= 12, was " + oldest);
        for (long t = oldest; t <= 20; t++) {
            WorldSnapshot replay = pruned.rewindTo(t);
            WorldSnapshot direct = full.rewindTo(t);
            assertEquals(direct.tick(), replay.tick());
            assertEquals(direct.entities(), replay.entities(),
                    "replay at tick " + t + " must equal direct stepping");
        }
    }

    @Test
    void replayFromScratchEqualsStored() {
        ServerEngine e = new ServerEngine(props(256));
        script(e);
        // 重新构造一个同输入引擎，逐步推进，比较每个快照
        ServerEngine e2 = new ServerEngine(props(256));
        e2.addPlayer("a");
        e2.addPlayer("b");
        for (long t = 1; t <= 20; t++) {
            long clientTime = t * TICK_NANOS;
            long recv = clientTime;
            int mx = t <= 10 ? 1 : 0;
            int my = t <= 10 ? 0 : 1;
            e2.submitInput("a", new PlayerInput(t, clientTime, mx, my, t == 15), recv);
            e2.submitInput("b", new PlayerInput(t, clientTime, 0, 0, t == 18), recv);
            WorldSnapshot s2 = e2.tick();
            WorldSnapshot s1 = e.snapshotAt(t).orElseThrow();
            assertEquals(s1.entities(), s2.entities(), "frame " + t + " must be identical");
        }
    }
}
