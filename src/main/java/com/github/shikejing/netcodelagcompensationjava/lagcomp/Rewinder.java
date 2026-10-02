package com.github.shikejing.netcodelagcompensationjava.lagcomp;

import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;

/**
 * 回退器：返回指定 tick 的权威世界（直接取快照或确定性回放）。
 */
@FunctionalInterface
public interface Rewinder {
    WorldSnapshot rewindTo(long tick);
}
