package com.github.shikejing.netcodelagcompensationjava.snapshot;

import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 权威快照历史。按 tick 索引（TreeMap），因此快照乱序到达也不会破坏时间轴：
 * 任意时刻都能取到 <= 指定 tick 的最近快照。
 *
 * 同时保存每个 tick 实际应用的输入，用于确定性回放（replay）。
 * 容量有界，超出后丢弃最旧快照；丢弃边界是确定的（见 oldestTick）。
 */
public class SnapshotHistory {

    private final int capacity;
    private final TreeMap<Long, WorldSnapshot> snapshots = new TreeMap<>();
    private final TreeMap<Long, Map<String, PlayerInput>> inputsByTick = new TreeMap<>();

    public SnapshotHistory(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** 记录快照。同一 tick 只保留首次发布的结果（已发布权威结果不可改写）。 */
    public synchronized void record(WorldSnapshot snapshot) {
        long tick = snapshot.tick();
        snapshots.putIfAbsent(tick, snapshot);
        prune();
    }

    public synchronized void recordInputs(long tick, Map<String, PlayerInput> inputs) {
        inputsByTick.putIfAbsent(tick, Map.copyOf(inputs));
        while (inputsByTick.size() > capacity) {
            inputsByTick.pollFirstEntry();
        }
    }

    public synchronized Optional<WorldSnapshot> get(long tick) {
        return Optional.ofNullable(snapshots.get(tick));
    }

    /** 不晚于 tick 的最近快照（回放起点）。 */
    public synchronized Optional<WorldSnapshot> floorSnapshot(long tick) {
        var e = snapshots.floorEntry(tick);
        return Optional.ofNullable(e == null ? null : e.getValue());
    }

    /** 严格大于 tick 的最近快照（插值窗口上界）。 */
    public synchronized Optional<WorldSnapshot> ceilSnapshot(long tick) {
        var e = snapshots.higherEntry(tick);
        return Optional.ofNullable(e == null ? null : e.getValue());
    }

    public synchronized Optional<WorldSnapshot> latest() {
        return snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.lastEntry().getValue());
    }

    public synchronized Optional<WorldSnapshot> oldest() {
        return snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.firstEntry().getValue());
    }

    public synchronized Optional<Map<String, PlayerInput>> inputsAt(long tick) {
        return Optional.ofNullable(inputsByTick.get(tick));
    }

    public synchronized long latestTick() {
        return snapshots.isEmpty() ? -1L : snapshots.lastKey();
    }

    public synchronized long oldestTick() {
        return snapshots.isEmpty() ? -1L : snapshots.firstKey();
    }

    public synchronized int size() { return snapshots.size(); }

    public synchronized int capacity() { return capacity; }

    private void prune() {
        while (snapshots.size() > capacity) {
            snapshots.pollFirstEntry();
        }
    }
}
