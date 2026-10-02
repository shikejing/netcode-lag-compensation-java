package com.github.shikejing.netcodelagcompensationjava.journal;

import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.HitRecord;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 权威日志：每个 tick 追加快照、实际应用的输入与命中输出。
 * 重启后加载最新状态继续，tick 与命中 id 单调连续，不跳变、不重复。
 */
public interface Journal {

    record JournalState(long tick,
                        WorldSnapshot snapshot,
                        Map<String, PlayerInput> lastInputs,
                        List<HitRecord> hitRecords) { }

    void appendTick(long tick, WorldSnapshot snapshot,
                    Map<String, PlayerInput> inputs, List<HitRecord> hits);

    Optional<JournalState> loadLatest();

    /** 加载全部 tick 状态（用于重启后重建历史）。默认仅返回最新。 */
    default java.util.List<JournalState> loadAll() {
        return loadLatest().map(java.util.List::of).orElse(java.util.List.of());
    }
}
