package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.config.NetcodeProperties;
import com.github.shikejing.netcodelagcompensationjava.engine.ServerEngine;
import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.snapshot.EntityState;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发推进：输入线程与服务端推进并发进行；
 * 任一时刻对外快照都是自洽的不可变状态；慢客户端不阻塞其他客户端。
 */
class ConcurrencyTest {

    private static final int TICK_RATE = 60;
    private static final long TN = 1_000_000_000L / TICK_RATE;

    private NetcodeProperties props() {
        NetcodeProperties p = new NetcodeProperties();
        p.setTickRate(TICK_RATE);
        p.setFixedSeed(3L);
        return p;
    }

    @Test
    void concurrentSubmissionsProduceConsistentSnapshots() throws InterruptedException {
        ServerEngine engine = new ServerEngine(props());
        int players = 4;
        int ticks = 30;
        for (int i = 0; i < players; i++) engine.addPlayer("p" + i);

        ExecutorService pool = Executors.newFixedThreadPool(players);
        AtomicInteger errors = new AtomicInteger(0);

        // 每个玩家一个输入线程，并发提交全部输入
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            final String pid = "p" + i;
            final int dir = (i % 2 == 0) ? 1 : -1;
            futures.add(pool.submit(() -> {
                try {
                    for (long t = 1; t <= ticks; t++) {
                        long clientTime = t * TN;
                        engine.submitInput(pid, new PlayerInput(t, clientTime, dir, 0, false), clientTime);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }));
        }
        for (var f : futures) {
            assertDoesNotThrow(() -> f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertEquals(0, errors.get(), "no submission errors");
        // 推进线程（主线程）固定步长推进
        for (long t = 1; t <= ticks; t++) {
            WorldSnapshot snap = engine.tick();
            // 自洽性：快照中每个实体状态完整，无部分应用
            assertNotNull(snap);
            assertEquals(t, snap.tick());
            for (EntityState e : snap.entities().values()) {
                assertNotNull(e.playerId());
                assertTrue(e.health() >= 0 && e.health() <= 100);
                assertTrue(e.x() >= 0 && e.x() <= 100);
                assertTrue(e.y() >= 0 && e.y() <= 100);
            }
        }

        // 最终快照：每个玩家都移动了 ticks 次（方向不同）
        WorldSnapshot last = engine.latestSnapshot().orElseThrow();
        for (int i = 0; i < players; i++) {
            EntityState e = last.entity("p" + i);
            double expectedX = 20.0 + (i % 4) * 20.0 + (i % 2 == 0 ? ticks * 2.0 : -ticks * 2.0);
            expectedX = Math.max(0.0, Math.min(100.0, expectedX));
            assertEquals(expectedX, e.x(), 1e-6, "player p" + i + " should move every tick");
        }
    }

    @Test
    void slowClientDoesNotBlockEngine() {
        ServerEngine engine = new ServerEngine(props());
        engine.addPlayer("fast");
        engine.addPlayer("slow");
        // fast 每 tick 提交；slow 只在第 1 tick 提交（之后沉默）
        for (long t = 1; t <= 20; t++) {
            engine.submitInput("fast", new PlayerInput(t, t * TN, 1, 0, false), t * TN);
            if (t == 1) engine.submitInput("slow", new PlayerInput(1, TN, 0, 1, false), TN);
            WorldSnapshot snap = engine.tick();
            // 每个 tick 都有 fast 的移动
            EntityState fast = snap.entity("fast");
            double expectedFastX = Math.min(100.0, 20.0 + t * 2.0);
            assertEquals(expectedFastX, fast.x(), 1e-6, "fast must advance every tick regardless of slow client");
        }
        // slow 只移动一次
        EntityState slow = engine.latestSnapshot().orElseThrow().entity("slow");
        assertEquals(22.0d, slow.y(), 1e-6);
    }

    @Test
    void publishedSnapshotIsImmutable() {
        ServerEngine engine = new ServerEngine(props());
        engine.addPlayer("a");
        engine.submitInput("a", new PlayerInput(1, TN, 1, 0, false), TN);
        WorldSnapshot s1 = engine.tick();
        // 再推进，s1 不应改变
        engine.submitInput("a", new PlayerInput(2, 2 * TN, 1, 0, false), 2 * TN);
        WorldSnapshot s2 = engine.tick();
        assertNotSame(s1, s2);
        assertEquals(22.0d, s1.entity("a").x(), 1e-9);
        assertEquals(24.0d, s2.entity("a").x(), 1e-9);
        // s1 不可变
        assertThrows(UnsupportedOperationException.class, () -> s1.entities().put("x", null));
    }
}
