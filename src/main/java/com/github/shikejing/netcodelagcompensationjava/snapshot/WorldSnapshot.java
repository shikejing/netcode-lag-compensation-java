package com.github.shikejing.netcodelagcompensationjava.snapshot;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 权威世界快照。不可变对象：发布后任何线程看到的都是同一个自洽状态。
 * entities 使用 TreeMap 保证遍历顺序确定（逐帧一致输出的基础）。
 */
public record WorldSnapshot(
        long tick,
        Map<String, EntityState> entities
) {
    public WorldSnapshot {
        entities = Collections.unmodifiableMap(new TreeMap<>(entities));
    }

    public EntityState entity(String playerId) {
        return entities.get(playerId);
    }

    public boolean hasEntity(String playerId) {
        return entities.containsKey(playerId);
    }

    public int entityCount() {
        return entities.size();
    }
}
