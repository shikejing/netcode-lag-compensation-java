package com.github.shikejing.netcodelagcompensationjava.snapshot;

import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;

import java.util.Map;
import java.util.TreeMap;

/**
 * Append-only history of authoritative snapshots keyed by tick. Retrieval and
 * replay at any retained tick returns exactly the snapshot produced when the
 * server stepped there (snapshots are immutable), so replay matches a direct
 * run to that tick. Pruning follows a single explicit rule: after a new
 * snapshot N is appended, ticks strictly older than {@code N - retainTicks}
 * are dropped. All access is thread-safe.
 */
public final class SnapshotHistory {

    private final int retainTicks;
    private final TreeMap<Long, WorldSnapshot> byTick = new TreeMap<>();

    public SnapshotHistory(int retainTicks) {
        if (retainTicks < 0) {
            throw new IllegalArgumentException("retainTicks must be >= 0");
        }
        this.retainTicks = retainTicks;
    }


    public synchronized void append(WorldSnapshot snapshot) {
        Long tick = snapshot.tick();
        // Overwrite protection: a published snapshot is never rewritten.
        if (byTick.containsKey(tick)) {
            throw new IllegalStateException("snapshot for tick " + tick + " already published");
        }
        byTick.put(tick, snapshot);
        pruneOlderThan(tick);
    }

    public synchronized WorldSnapshot at(long tick) {
        return byTick.get(tick);
    }

    public synchronized boolean has(long tick) {
        return byTick.containsKey(tick);
    }

    public synchronized WorldSnapshot oldest() {
        return byTick.isEmpty() ? null : byTick.firstEntry().getValue();
    }

    public synchronized WorldSnapshot latest() {
        return byTick.isEmpty() ? null : byTick.lastEntry().getValue();
    }

    public synchronized int size() {
        return byTick.size();
    }

    /** Snapshot with the greatest tick <= {@code tick}, or {@code null}. */
    public synchronized WorldSnapshot atOrBefore(long tick) {
        Map.Entry<Long, WorldSnapshot> e = byTick.floorEntry(tick);
        return e == null ? null : e.getValue();
    }

    /** Snapshot with the smallest tick >= {@code tick}, or {@code null}. */
    public synchronized WorldSnapshot atOrAfter(long tick) {
        Map.Entry<Long, WorldSnapshot> e = byTick.ceilingEntry(tick);
        return e == null ? null : e.getValue();
    }

    /** True when the two retained ticks are not consecutive (a snapshot was lost). */
    public synchronized boolean hasGap(long lowerTick, long upperTick) {
        for (long t = lowerTick + 1; t < upperTick; t++) {
            if (!byTick.containsKey(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drop snapshots strictly older than {@code tick - retainTicks}.
     * Boundary: the snapshot exactly {@code retainTicks} behind {@code tick}
     * is still retained (window size = retainTicks + 1).
     */
    public synchronized void pruneOlderThan(long tick) {
        long keepFrom = tick - retainTicks;
        while (!byTick.isEmpty() && byTick.firstKey() < keepFrom) {
            byTick.pollFirstEntry();
        }
    }
}
