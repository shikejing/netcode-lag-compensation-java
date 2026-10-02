package com.github.shikejing.netcodelagcompensationjava.snapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 快照插值器。
 *
 * 在两个权威快照之间对观察者可见位置做线性插值。边界行为确定：
 *  - renderTick 不晚于最旧快照 -> CLAMPED_OLDEST（取最旧快照，alpha=0）；
 *  - renderTick 不早于最新快照 -> CLAMPED_LATEST（取最新快照，alpha=1）；
 *  - 没有任何快照 -> NO_DATA。
 *
 * 快照缺失/乱序：历史按 tick 索引，乱序到达不影响取值；
 * 实体只在一侧快照存在时标记 CLAMPED_SINGLE 并取该侧值，
 * 绝不把缺失当成静止或零值。
 */
public class Interpolator {

    public enum Boundary { OK, CLAMPED_OLDEST, CLAMPED_LATEST, NO_DATA }
    public enum EntityStatus { INTERPOLATED, CLAMPED_SINGLE, MISSING }

    public record InterpolatedEntity(EntityStatus status, double x, double y,
                                     double aimX, double aimY, int health, boolean alive) { }

    public record Result(Boundary boundary, long fromTick, long toTick, double alpha,
                         Map<String, InterpolatedEntity> entities) { }

    public Optional<Result> interpolate(SnapshotHistory history, long renderTick) {
        Optional<WorldSnapshot> latestOpt = history.latest();
        if (latestOpt.isEmpty()) {
            return Optional.of(new Result(Boundary.NO_DATA, -1L, -1L, 0.0d, Map.of()));
        }
        WorldSnapshot latest = latestOpt.get();
        WorldSnapshot oldest = history.oldest().orElseThrow();

        if (renderTick <= oldest.tick()) {
            return Optional.of(buildClamped(Boundary.CLAMPED_OLDEST, oldest));
        }
        if (renderTick >= latest.tick()) {
            return Optional.of(buildClamped(Boundary.CLAMPED_LATEST, latest));
        }

        WorldSnapshot floor = history.floorSnapshot(renderTick).orElse(oldest);
        WorldSnapshot ceil = history.ceilSnapshot(renderTick).orElse(latest);

        if (floor.tick() == ceil.tick()) {
            return Optional.of(buildClamped(Boundary.OK, floor));
        }
        double alpha = (double) (renderTick - floor.tick()) / (double) (ceil.tick() - floor.tick());
        alpha = Math.max(0.0d, Math.min(1.0d, alpha));

        Map<String, InterpolatedEntity> out = new TreeMap<>();
        for (String id : floor.entities().keySet()) {
            EntityState a = floor.entity(id);
            EntityState b = ceil.entity(id);
            if (b == null) {
                out.put(id, new InterpolatedEntity(EntityStatus.CLAMPED_SINGLE,
                        a.x(), a.y(), a.aimX(), a.aimY(), a.health(), a.alive()));
            } else {
                out.put(id, new InterpolatedEntity(EntityStatus.INTERPOLATED,
                        lerp(a.x(), b.x(), alpha), lerp(a.y(), b.y(), alpha),
                        lerp(a.aimX(), b.aimX(), alpha), lerp(a.aimY(), b.aimY(), alpha),
                        a.health(), a.alive()));
            }
        }
        for (String id : ceil.entities().keySet()) {
            if (!out.containsKey(id)) {
                EntityState b = ceil.entity(id);
                out.put(id, new InterpolatedEntity(EntityStatus.CLAMPED_SINGLE,
                        b.x(), b.y(), b.aimX(), b.aimY(), b.health(), b.alive()));
            }
        }
        return Optional.of(new Result(Boundary.OK, floor.tick(), ceil.tick(), alpha, out));
    }

    private Result buildClamped(Boundary boundary, WorldSnapshot s) {
        Map<String, InterpolatedEntity> out = new HashMap<>();
        for (EntityState e : s.entities().values()) {
            out.put(e.playerId(), new InterpolatedEntity(EntityStatus.CLAMPED_SINGLE,
                    e.x(), e.y(), e.aimX(), e.aimY(), e.health(), e.alive()));
        }
        return new Result(boundary, s.tick(), s.tick(),
                boundary == Boundary.CLAMPED_OLDEST ? 0.0d : 1.0d, Map.copyOf(out));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
