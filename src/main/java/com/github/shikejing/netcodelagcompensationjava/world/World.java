package com.github.shikejing.netcodelagcompensationjava.world;

import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.HitRecord;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.HitStatus;
import com.github.shikejing.netcodelagcompensationjava.snapshot.EntityState;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 确定性权威世界模拟器。
 * 给定前一个快照、本 tick 输入与命中结果，产出新快照。
 * 纯函数式：不持有可变状态，相同输入必然得到相同结果（逐帧一致）。
 */
public class World {

    public static final double ARENA_SIZE = 100.0d;
    public static final double MOVE_SPEED_PER_TICK = 2.0d;
    public static final double HIT_RADIUS = 3.0d;
    public static final int MAX_HEALTH = 100;
    public static final int DAMAGE_PER_HIT = 25;

    private final long seed;
    private final int tickRate;

    public World(long seed, int tickRate) {
        this.seed = seed;
        this.tickRate = tickRate;
    }

    public long seed() { return seed; }

    /** 为中途加入的玩家生成出生状态（位置规则与 initial 一致）。 */
    public static EntityState spawnState(String playerId, int index) {
        double x = 20.0d + (index % 4) * 20.0d;
        double y = 20.0d + (index / 4) * 20.0d;
        return EntityState.alive(playerId, x, y, 1.0d, 0.0d, MAX_HEALTH);
    }

    /** 在不改变已有实体的前提下，为缺失玩家补出生状态（确定性）。 */
    public static WorldSnapshot withSpawns(WorldSnapshot prev, java.util.Collection<String> playerIds) {
        Map<String, EntityState> next = new TreeMap<>(prev.entities());
        int index = 0;
        for (String id : playerIds) {
            if (!next.containsKey(id)) {
                next.put(id, spawnState(id, index));
            }
            index++;
        }
        return new WorldSnapshot(prev.tick(), next);
    }

    /** 初始快照：玩家按 id 字典序确定地分布在场地中。 */
    public WorldSnapshot initial(List<String> playerIds) {
        Map<String, EntityState> entities = new TreeMap<>();
        List<String> sorted = new ArrayList<>(playerIds);
        sorted.sort(Comparator.naturalOrder());
        for (int i = 0; i < sorted.size(); i++) {
            String id = sorted.get(i);
            double x = 20.0d + (i % 4) * 20.0d;
            double y = 20.0d + (i / 4) * 20.0d;
            entities.put(id, EntityState.alive(id, x, y, 1.0d, 0.0d, MAX_HEALTH));
        }
        return new WorldSnapshot(0L, entities);
    }

    /**
     * 推进一步。
     * @param hits 本 tick 经延迟补偿判定为 HIT 的命中记录（伤害在移动后结算）
     */
    public WorldSnapshot advance(WorldSnapshot prev, Map<String, PlayerInput> inputs,
                                 long tick, Random rng, List<HitRecord> hits) {
        Map<String, EntityState> next = new TreeMap<>();
        for (EntityState e : prev.entities().values()) {
            PlayerInput in = inputs.get(e.playerId());
            EntityState moved = moveEntity(e, in);
            next.put(e.playerId(), moved);
        }
        // 伤害结算：仅当命中发生在本 tick 之前的回退时刻，且射手/受害者仍然存活有效
        for (HitRecord hit : hits) {
            if (hit.status() != HitStatus.HIT) continue;
            EntityState victim = next.get(hit.victimId());
            if (victim == null || !victim.alive()) continue;
            int newHealth = Math.max(0, victim.health() - DAMAGE_PER_HIT);
            next.put(hit.victimId(), new EntityState(
                    victim.playerId(), victim.x(), victim.y(),
                    victim.aimX(), victim.aimY(), newHealth, newHealth > 0));
        }
        return new WorldSnapshot(tick, next);
    }

    private EntityState moveEntity(EntityState e, PlayerInput in) {
        if (!e.alive()) {
            return e;
        }
        double x = e.x();
        double y = e.y();
        double aimX = e.aimX();
        double aimY = e.aimY();
        if (in != null) {
            double mx = in.moveX();
            double my = in.moveY();
            double len = Math.hypot(mx, my);
            if (len > 0) {
                double nx = mx / len;
                double ny = my / len;
                x += nx * MOVE_SPEED_PER_TICK;
                y += ny * MOVE_SPEED_PER_TICK;
                aimX = nx;
                aimY = ny;
            }
        }
        x = Math.max(0.0d, Math.min(ARENA_SIZE, x));
        y = Math.max(0.0d, Math.min(ARENA_SIZE, y));
        return new EntityState(e.playerId(), x, y, aimX, aimY, e.health(), true);
    }

    /**
     * 命中扫描：从射手位置沿瞄准方向发射射线，判定与其他存活玩家命中圆的相交。
     * 纯几何判定，无随机因素。
     */
    public static HitscanResult hitscan(WorldSnapshot world, String shooterId,
                                        double aimX, double aimY) {
        EntityState shooter = world.entity(shooterId);
        if (shooter == null || !shooter.alive()) {
            return new HitscanResult(false, null, Double.NaN, Double.NaN);
        }
        double len = Math.hypot(aimX, aimY);
        if (len == 0) {
            return new HitscanResult(false, null, Double.NaN, Double.NaN);
        }
        double dx = aimX / len;
        double dy = aimY / len;
        EntityState best = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (EntityState e : world.entities().values()) {
            if (e.playerId().equals(shooterId) || !e.alive()) continue;
            double ox = e.x() - shooter.x();
            double oy = e.y() - shooter.y();
            double t = ox * dx + oy * dy;
            if (t < 0) continue; // 目标在射手身后
            double perp2 = ox * ox + oy * oy - t * t;
            if (perp2 <= HIT_RADIUS * HIT_RADIUS && t < bestT) {
                best = e;
                bestT = t;
            }
        }
        if (best == null) {
            return new HitscanResult(false, null, Double.NaN, Double.NaN);
        }
        double px = shooter.x() + dx * bestT;
        double py = shooter.y() + dy * bestT;
        return new HitscanResult(true, best.playerId(), px, py);
    }

    public record HitscanResult(boolean hit, String victimId, double pointX, double pointY) { }
}
