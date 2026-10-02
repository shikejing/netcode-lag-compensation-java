package com.github.shikejing.netcodelagcompensationjava.engine;

import com.github.shikejing.netcodelagcompensationjava.config.NetcodeProperties;
import com.github.shikejing.netcodelagcompensationjava.input.InputQueue;
import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.journal.Journal;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.HitRecord;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.HitStatus;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.LagCompensator;
import com.github.shikejing.netcodelagcompensationjava.snapshot.SnapshotHistory;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;
import com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator;
import com.github.shikejing.netcodelagcompensationjava.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务端权威引擎。
 *
 * 固定步长推进权威世界；输入处理与推进并发进行：
 *  - 输入线程只写入每玩家的并发队列与时钟样本，绝不触碰世界状态；
 *  - 唯一的推进线程在每个 tick 把不可变快照发布出去，
 *    任一时刻对外可见的都是某个自洽快照，不存在部分应用输入的中间状态；
 *  - 慢玩家没有输入时该 tick 直接跳过，不阻塞其他玩家推进。
 */
public class ServerEngine {

    private final NetcodeProperties properties;
    private final World world;
    private final SnapshotHistory history;
    private final LagCompensator lagCompensator;
    private final Journal journal;

    private final Set<String> players = ConcurrentHashMap.newKeySet();
    private final Map<String, InputQueue> queues = new ConcurrentHashMap<>();
    private final Map<String, ClockEstimator> clocks = new ConcurrentHashMap<>();

    private volatile WorldSnapshot latest = null;
    private final List<HitRecord> hitRecords = new ArrayList<>();
    private final AtomicLong hitIdSequence = new AtomicLong(0L);

    private Thread loopThread;
    private volatile boolean running = false;

    public ServerEngine(NetcodeProperties properties) {
        this(properties, null);
    }

    public ServerEngine(NetcodeProperties properties, Journal journal) {
        this.properties = properties;
        this.world = new World(properties.getFixedSeed(), properties.getTickRate());
        this.history = new SnapshotHistory(properties.getSnapshotHistoryTicks());
        this.lagCompensator = new LagCompensator(properties.getMaxRewindTicks());
        this.journal = journal;
        // 初始权威快照（tick 0，尚无玩家）；保证未 start() 也有自洽快照可查
        this.latest = world.initial(List.of());
        history.record(this.latest);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public synchronized void start() {
        start(true);
    }

    /** 启动引擎。withLoop=false 时不启动固定步长循环线程（用于手动推进的测试）。 */
    public synchronized void start(boolean withLoop) {
        if (running) return;
        // 从日志恢复：tick 与命中 id 单调连续，不跳变、不重复
        if (journal != null) {
            Optional<Journal.JournalState> state = journal.loadLatest();
            if (state.isPresent()) {
                Journal.JournalState s = state.get();
                restore(s);
            }
        }
        if (latest == null) {
            latest = world.initial(List.of());
            history.record(latest);
        }
        if (withLoop) {
            running = true;
            loopThread = new Thread(this::runLoop, "server-tick-loop");
            loopThread.setDaemon(true);
            loopThread.start();
        }
    }

    public synchronized void stop() {
        running = false;
        if (loopThread != null) {
            loopThread.interrupt();
            try { loopThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            loopThread = null;
        }
    }

    private void restore(Journal.JournalState s) {
        // 从日志重建历史（快照 + 输入），支持重启后回放
        java.util.List<Journal.JournalState> all = journal.loadAll();
        if (all.size() > 1) {
            for (Journal.JournalState st : all) {
                history.record(st.snapshot());
                history.recordInputs(st.tick(), st.lastInputs());
            }
        } else {
            history.record(s.snapshot());
            history.recordInputs(s.tick(), s.lastInputs());
        }
        latest = s.snapshot();
        // 命中记录：去重后接续，id 单调不重复
        for (HitRecord r : s.hitRecords()) {
            if (hitIdSequence.get() < r.id()) hitIdSequence.set(r.id());
            if (!containsHitId(r.id())) {
                synchronized (hitRecords) { hitRecords.add(r); }
            }
        }
        hitIdSequence.incrementAndGet();
    }

    private boolean containsHitId(long id) {
        synchronized (hitRecords) {
            for (HitRecord r : hitRecords) if (r.id() == id) return true;
        }
        return false;
    }

    private void runLoop() {
        long tickNanos = 1_000_000_000L / properties.getTickRate();
        long nextTickTime = System.nanoTime() + tickNanos;
        while (running) {
            long now = System.nanoTime();
            if (now < nextTickTime) {
                try { Thread.sleep((nextTickTime - now) / 1_000_000L, 1); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            nextTickTime += tickNanos;
            try {
                tick();
            } catch (RuntimeException e) {
                // 单 tick 异常不应杀死推进线程；记录后继续
                System.err.println("[ServerEngine] tick failed: " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 输入（可由多线程并发调用）
    // ------------------------------------------------------------------

    public void addPlayer(String playerId) {
        players.add(playerId);
        queues.computeIfAbsent(playerId, InputQueue::new);
        clocks.computeIfAbsent(playerId,
                id -> new ClockEstimator(properties.getClockSmoothingAlpha(),
                        (long) properties.getClockClampNanos()));
    }

    public InputQueue.InputDecision submitInput(String playerId, PlayerInput input) {
        return submitInput(playerId, input, System.nanoTime());
    }

    public InputQueue.InputDecision submitInput(String playerId, PlayerInput input, long serverRecvNanos) {
        addPlayer(playerId);
        ClockEstimator clock = clocks.get(playerId);
        clock.addSample(input.clientTimeNanos(), serverRecvNanos);
        return queues.get(playerId).submit(input);
    }

    // ------------------------------------------------------------------
    // 固定步长推进（仅推进线程调用；测试可手动调用）
    // ------------------------------------------------------------------

    public WorldSnapshot tick() {
        long tick = Math.max(1L, history.latestTick() + 1);
        WorldSnapshot prev = history.latest().orElseGet(() -> world.initial(List.of()));

        List<String> sortedPlayers = new ArrayList<>(players);
        sortedPlayers.sort(Comparator.naturalOrder());

        // 本 tick 开火判定用的世界：已补出生、尚未移动（射手据此观察到的状态）
        WorldSnapshot worldForShots = World.withSpawns(prev, sortedPlayers);

        // 1) 延迟补偿命中判定：开火输入按射手视角回退到其输入时刻
        //    （必须在移动调度之前判定，因为 pollDue 会把到期输入移出 pending）
        List<HitRecord> newHits = new ArrayList<>();
        List<HitRecord> confirmedHits = new ArrayList<>();
        for (String playerId : sortedPlayers) {
            InputQueue queue = queues.get(playerId);
            ClockEstimator clock = clocks.get(playerId);
            if (queue == null || clock == null) continue;
            List<PlayerInput> fires = queue.dueFireInputs(tick, clock, properties.getTickRate());
            for (PlayerInput in : fires) {
                var shooter = worldForShots.entity(playerId);
                double aimX = shooter == null ? 1.0d : shooter.aimX();
                double aimY = shooter == null ? 0.0d : shooter.aimY();
                long hitId = hitIdSequence.incrementAndGet();
                // 回退到 mapped tick；若 mapped == 当前 tick（尚未存储），用本 tick 开火世界
                HitRecord record = lagCompensator.evaluate(
                        new LagCompensator.ShotRequest(playerId, in.clientTimeNanos(), aimX, aimY),
                        tick, hitId, clock,
                        t -> t == tick ? worldForShots : rewindTo(t),
                        properties.getTickRate());
                newHits.add(record);
                if (record.status() == HitStatus.HIT) confirmedHits.add(record);
            }
        }

        // 2) 收集本 tick 移动输入（按玩家字典序，确定）
        Map<String, PlayerInput> inputs = new java.util.TreeMap<>();
        for (String playerId : sortedPlayers) {
            InputQueue queue = queues.get(playerId);
            ClockEstimator clock = clocks.get(playerId);
            if (queue == null || clock == null) continue;
            List<PlayerInput> due = queue.pollDue(tick, clock, properties.getTickRate());
            PlayerInput picked = queue.pickNewestOrSupersede(due);
            if (picked != null) inputs.put(playerId, picked);
        }

        // 3) 生成并发布新快照（不可变）
        WorldSnapshot next = world.advance(worldForShots, inputs, tick,
                new Random(properties.getFixedSeed() ^ tick), confirmedHits);

        // 4) 记录历史与日志
        history.record(next);
        history.recordInputs(tick, inputs);
        synchronized (hitRecords) { hitRecords.addAll(newHits); }
        if (journal != null) {
            journal.appendTick(tick, next, inputs, newHits);
        }
        latest = next;
        return next;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Optional<WorldSnapshot> latestSnapshot() {
        return Optional.ofNullable(latest);
    }

    public long currentTick() {
        return history.latestTick();
    }

    public long oldestSnapshotTick() {
        return history.oldestTick();
    }

    public Optional<WorldSnapshot> snapshotAt(long tick) {
        if (tick < 0 || tick > history.latestTick()) return Optional.empty();
        return Optional.of(rewindTo(tick));
    }

    /** 回放到任意历史时刻：优先取已存快照，缺失则从最近快照确定性重放。
     *  若目标 tick 早于最旧快照（历史已剪枝），无法重建，返回 null。 */
    public WorldSnapshot rewindTo(long tick) {
        if (tick < 0) return null;
        WorldSnapshot direct = history.get(tick).orElse(null);
        if (direct != null) return direct;
        WorldSnapshot floor = history.floorSnapshot(tick).orElse(null);
        if (floor == null) return null;
        WorldSnapshot cur = floor;
        for (long t = floor.tick() + 1; t <= tick; t++) {
            Map<String, PlayerInput> ins = history.inputsAt(t).orElse(Map.of());
            cur = world.advance(cur, ins, t,
                    new Random(properties.getFixedSeed() ^ t), List.of());
        }
        return cur;
    }

    public ClockEstimator clockOf(String playerId) {
        return clocks.get(playerId);
    }

    public InputQueue queueOf(String playerId) {
        return queues.get(playerId);
    }

    public List<HitRecord> hitRecords() {
        synchronized (hitRecords) { return List.copyOf(hitRecords); }
    }

    public List<HitRecord> hitRecordsFor(String shooterId) {
        synchronized (hitRecords) {
            return hitRecords.stream().filter(r -> r.shooterId().equals(shooterId)).toList();
        }
    }

    public NetcodeProperties properties() { return properties; }
}
