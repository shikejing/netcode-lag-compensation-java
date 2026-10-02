package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.snapshot.EntityState;
import com.github.shikejing.netcodelagcompensationjava.snapshot.Interpolator;
import com.github.shikejing.netcodelagcompensationjava.snapshot.SnapshotHistory;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插值：两个权威快照之间线性插值；边界行为确定；
 * 快照乱序到达不影响；实体缺失时绝不按零值处理。
 */
class InterpolationTest {

    private WorldSnapshot snap(long tick, String id, double x, double y) {
        return new WorldSnapshot(tick, Map.of(id, EntityState.alive(id, x, y, 1.0d, 0.0d, 100)));
    }

    @Test
    void interpolatesBetweenTwoSnapshots() {
        SnapshotHistory h = new SnapshotHistory(16);
        h.record(snap(0, "a", 0.0d, 0.0d));
        h.record(snap(10, "a", 10.0d, 20.0d));
        Interpolator.Result r = new Interpolator().interpolate(h, 5).orElseThrow();
        assertEquals(Interpolator.Boundary.OK, r.boundary());
        assertEquals(0L, r.fromTick());
        assertEquals(10L, r.toTick());
        assertEquals(0.5d, r.alpha(), 1e-9);
        Interpolator.InterpolatedEntity e = r.entities().get("a");
        assertEquals(Interpolator.EntityStatus.INTERPOLATED, e.status());
        assertEquals(5.0d, e.x(), 1e-9);
        assertEquals(10.0d, e.y(), 1e-9);
    }

    @Test
    void clampsAtBoundaries() {
        SnapshotHistory h = new SnapshotHistory(16);
        h.record(snap(0, "a", 0.0d, 0.0d));
        h.record(snap(10, "a", 10.0d, 0.0d));
        Interpolator interp = new Interpolator();

        Interpolator.Result before = interp.interpolate(h, -5).orElseThrow();
        assertEquals(Interpolator.Boundary.CLAMPED_OLDEST, before.boundary());
        assertEquals(0.0d, before.entities().get("a").x(), 1e-9);

        Interpolator.Result after = interp.interpolate(h, 15).orElseThrow();
        assertEquals(Interpolator.Boundary.CLAMPED_LATEST, after.boundary());
        assertEquals(10.0d, after.entities().get("a").x(), 1e-9);
    }

    @Test
    void missingEntityFlaggedNotZero() {
        SnapshotHistory h = new SnapshotHistory(16);
        // tick 0 只有 a；tick 10 有 a 和 b
        h.record(new WorldSnapshot(0, Map.of("a", EntityState.alive("a", 0.0d, 0.0d, 1.0d, 0.0d, 100))));
        h.record(new WorldSnapshot(10, Map.of(
                "a", EntityState.alive("a", 10.0d, 0.0d, 1.0d, 0.0d, 100),
                "b", EntityState.alive("b", 50.0d, 50.0d, 0.0d, 1.0d, 100))));
        Interpolator.Result r = new Interpolator().interpolate(h, 5).orElseThrow();
        // b 在 floor 快照缺失 -> CLAMPED_SINGLE，取 ceil 侧值，绝不是 0
        Interpolator.InterpolatedEntity b = r.entities().get("b");
        assertEquals(Interpolator.EntityStatus.CLAMPED_SINGLE, b.status());
        assertEquals(50.0d, b.x(), 1e-9);
        assertEquals(50.0d, b.y(), 1e-9);
    }

    @Test
    void outOfOrderArrivalDoesNotBreakInterpolation() {
        SnapshotHistory h = new SnapshotHistory(16);
        // 乱序到达：先到 tick 10，再到 tick 0，再到 tick 5
        h.record(snap(10, "a", 10.0d, 0.0d));
        h.record(snap(0, "a", 0.0d, 0.0d));
        h.record(snap(5, "a", 5.0d, 0.0d));
        Interpolator.Result r = new Interpolator().interpolate(h, 7).orElseThrow();
        assertEquals(Interpolator.Boundary.OK, r.boundary());
        assertEquals(5L, r.fromTick());
        assertEquals(10L, r.toTick());
        // alpha = (7-5)/(10-5) = 0.4 -> x = 5 + 0.4*5 = 7
        assertEquals(7.0d, r.entities().get("a").x(), 1e-9);
    }

    @Test
    void noDataWhenEmpty() {
        SnapshotHistory h = new SnapshotHistory(16);
        Interpolator.Result r = new Interpolator().interpolate(h, 5).orElseThrow();
        assertEquals(Interpolator.Boundary.NO_DATA, r.boundary());
        assertTrue(r.entities().isEmpty());
    }
}
