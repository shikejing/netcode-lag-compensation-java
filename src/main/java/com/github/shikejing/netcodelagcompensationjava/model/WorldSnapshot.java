package com.github.shikejing.netcodelagcompensationjava.model;

import java.util.List;
import java.util.Map;

/**
 * Immutable authoritative world snapshot for one fixed tick.
 */
public record WorldSnapshot(
        long tick,
        long serverTimeMs,
        Map<Integer, PlayerState> players,
        long rngState,
        List<String> events
) {
    public WorldSnapshot {
        players = Map.copyOf(players);
        events = List.copyOf(events);
    }

    public PlayerState player(int clientId) {
        return players.get(clientId);
    }
}
