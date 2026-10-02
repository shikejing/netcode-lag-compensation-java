package com.github.shikejing.netcodelagcompensationjava.snapshot;

import com.github.shikejing.netcodelagcompensationjava.model.PlayerState;

import java.util.Map;

/**
 * Interpolation output. On non-OK statuses {@code players} is empty and
 * {@code reason} explains the boundary behavior — callers must not treat
 * absence of data as players standing still at the origin.
 */
public record InterpResult(
        InterpStatus status,
        long serverTimeMs,
        long lowerTick,
        long upperTick,
        double alpha,
        Map<Integer, PlayerState> players,
        String reason
) {
}
