package com.github.shikejing.netcodelagcompensationjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.netcodelagcompensationjava.config.NetcodeProperties;
import com.github.shikejing.netcodelagcompensationjava.engine.ServerEngine;
import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.journal.FileJournal;
import com.github.shikejing.netcodelagcompensationjava.journal.Journal;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性与重启：
 *  - 固定种子 + 相同输入 -> 逐帧输出一致；
 *  - 重启后从日志恢复，tick 与命中 id 单调连续，不跳变、不重复。
 */
class DeterminismAndRestartTest {

    private static final int TICK_RATE = 60;
    private static final long TN = 1_000_000_000L / TICK_RATE;

    private NetcodeProperties props(long seed) {
        NetcodeProperties p = new NetcodeProperties();
        p.setTickRate(TICK_RATE);
        p.setFixedSeed(seed);
        return p;
    }

    private void script(ServerEngine e, long ticks) {
        e.addPlayer("a");
        e.addPlayer("b");
        for (long t = 1; t <= ticks; t++) {
            long ct = t * TN;
            e.submitInput("a", new PlayerInput(t, ct, 1, 0, t == 5), ct);
            e.submitInput("b", new PlayerInput(t, ct, 0, 1, false), ct);
            e.tick();
        }
    }

    @Test
    void fixedSeedAndInputsProduceIdenticalFrames() {
        ServerEngine e1 = new ServerEngine(props(42L));
        ServerEngine e2 = new ServerEngine(props(42L));
        script(e1, 25);
        script(e2, 25);
        for (long t = 1; t <= 25; t++) {
            WorldSnapshot s1 = e1.snapshotAt(t).orElseThrow();
            WorldSnapshot s2 = e2.snapshotAt(t).orElseThrow();
            assertEquals(s1.entities(), s2.entities(), "frame " + t + " must be identical");
        }
        assertEquals(e1.hitRecords(), e2.hitRecords(), "hit records must be identical");
    }

    @Test
    void differentSeedMayDifferButStillConsistent() {
        ServerEngine e1 = new ServerEngine(props(1L));
        ServerEngine e2 = new ServerEngine(props(2L));
        script(e1, 10);
        script(e2, 10);
        // 两个引擎各自自洽（快照 tick 连续）
        for (long t = 1; t <= 10; t++) {
            assertEquals(t, e1.snapshotAt(t).orElseThrow().tick());
            assertEquals(t, e2.snapshotAt(t).orElseThrow().tick());
        }
    }

    @Test
    void restartResumesWithoutJumpOrDuplicate(@TempDir Path tempDir) {
        Path journalPath = tempDir.resolve("journal.jsonl");
        ObjectMapper mapper = new ObjectMapper();

        // 第一段：运行 10 tick
        ServerEngine first = new ServerEngine(props(42L), new FileJournal(journalPath, mapper));
        first.start(false);
        script(first, 10);
        first.stop();
        long firstTick = first.currentTick();
        int firstHits = first.hitRecords().size();
        assertEquals(10, firstTick);

        // 重启：从日志恢复
        ServerEngine restarted = new ServerEngine(props(42L), new FileJournal(journalPath, mapper));
        restarted.start(false);
        assertEquals(10, restarted.currentTick(), "restart must resume at tick 10, not jump");

        // 再推进 10 tick
        for (long t = 11; t <= 20; t++) {
            long ct = t * TN;
            restarted.submitInput("a", new PlayerInput(t, ct, 1, 0, false), ct);
            restarted.submitInput("b", new PlayerInput(t, ct, 0, 1, false), ct);
            restarted.tick();
        }
        restarted.stop();

        // tick 连续 1..20，无跳变
        assertEquals(20, restarted.currentTick());
        for (long t = 1; t <= 20; t++) {
            WorldSnapshot s = restarted.snapshotAt(t).orElse(null);
            if (t <= 10) {
                // 重启后历史只保留最新快照，更早的可从日志重放验证
                assertNotNull(s, "snapshot at tick " + t + " should be reconstructable");
            }
        }
        // 命中 id 单调不重复
        List<Long> ids = restarted.hitRecords().stream().map(h -> h.id()).toList();
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i) > ids.get(i - 1), "hit ids must be strictly increasing");
        }
        // 日志中无重复 tick
        FileJournal fj = new FileJournal(journalPath, mapper);
        List<Journal.JournalState> states = fj.loadAll();
        List<Long> ticks = states.stream().map(Journal.JournalState::tick).toList();
        for (int i = 1; i < ticks.size(); i++) {
            assertEquals(ticks.get(i - 1) + 1, ticks.get(i), "journal ticks must be contiguous, no duplicate");
        }
    }

    @Test
    void replayAfterRestartMatchesDirect(@TempDir Path tempDir) {
        Path journalPath = tempDir.resolve("journal2.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        ServerEngine first = new ServerEngine(props(7L), new FileJournal(journalPath, mapper));
        first.start(false);
        script(first, 15);
        first.stop();

        ServerEngine restarted = new ServerEngine(props(7L), new FileJournal(journalPath, mapper));
        restarted.start(false);
        // 重启后从日志重建历史：rewindTo 应能回放到任意历史时刻
        for (long t = 1; t <= 15; t++) {
            WorldSnapshot direct = first.snapshotAt(t).orElseThrow();
            WorldSnapshot replay = restarted.rewindTo(t);
            assertNotNull(replay, "replay at tick " + t + " should be available after restart");
            assertEquals(direct.entities(), replay.entities(),
                    "replay at tick " + t + " after restart must match direct");
        }
        restarted.stop();
    }
}
