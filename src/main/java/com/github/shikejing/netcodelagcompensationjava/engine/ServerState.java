package com.github.shikejing.netcodelagcompensationjava.engine;

import com.github.shikejing.netcodelagcompensationjava.model.WorldSnapshot;

/**
 * Serializable-ish minimal persistent state of the authoritative server,
 * enabling restart without state jumps or duplicated ticks.
 */
public record ServerState(
        long currentTick,
        long serverTimeMs,
        long rngState,
        WorldSnapshot latestSnapshot
) {
}
