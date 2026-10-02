package com.github.shikejing.netcodelagcompensationjava.input;

import com.github.shikejing.netcodelagcompensationjava.model.InputCommand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Thread-safe per-client input queues with (clientId, sequence) deduplication.
 * Ingestion runs concurrently with tick advancement; draining a tick takes and
 * removes exactly the commands targeting that tick, so a published world state
 * always reflects a complete, self-consistent input set.
 *
 * <p>Backlogs are per-client and bounded: one slow client filling its queue
 * never blocks or stalls any other client's advancement.
 */
public final class InputMailbox {

    private final int maxQueuePerClient;
    private final Map<Integer, TreeMap<Long, InputCommand>> queues = new HashMap<>();
    private final Map<Integer, Set<Integer>> seenSequences = new HashMap<>();
    private final Object lock = new Object();

    public InputMailbox(int maxQueuePerClient) {
        if (maxQueuePerClient <= 0) {
            throw new IllegalArgumentException("maxQueuePerClient must be > 0");
        }
        this.maxQueuePerClient = maxQueuePerClient;
    }

    public void registerClient(int clientId) {
        synchronized (lock) {
            queues.computeIfAbsent(clientId, k -> new TreeMap<>());
            seenSequences.computeIfAbsent(clientId, k -> new HashSet<>());
        }
    }

    /**
     * Ingest one command with its server-assigned target tick.
     *
     * @param currentTick newest tick already published (inputs at or below it
     *                    cannot change published results and are rejected)
     */
    public InputDisposition ingest(InputCommand command, long targetTick, long currentTick) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        int id = command.clientId();
        synchronized (lock) {
            if (!queues.containsKey(id)) {
                registerClient(id);
            }
            if (!seenSequences.get(id).add(command.sequence())) {
                return InputDisposition.DUPLICATE;
            }
            // Published ticks are immutable: a late input can never rewrite history.
            if (targetTick <= currentTick) {
                return InputDisposition.LATE_REJECTED;
            }
            if (queues.get(id).size() >= maxQueuePerClient) {
                // release the dedup reservation: it never entered the queue
                seenSequences.get(id).remove(command.sequence());
                return InputDisposition.BACKLOG_FULL;
            }
            queues.get(id).put(targetTick, command.withTargetTick(targetTick));
            return InputDisposition.ACCEPTED;
        }
    }

    /** Remove and return every queued command (any client) targeting exactly {@code tick}. */
    public List<InputCommand> drainTick(long tick) {
        List<InputCommand> out = new ArrayList<>();
        synchronized (lock) {
            for (TreeMap<Long, InputCommand> q : queues.values()) {
                InputCommand cmd = q.remove(tick);
                if (cmd != null) {
                    out.add(cmd);
                }
            }
        }
        return out;
    }

    /**
     * Drop only commands that could never be drained again: ones targeting a
     * tick strictly older than {@code tick}. Commands at {@code tick} or in the
     * future are retained (future ones are still waiting for their tick).
     */
    public void dropOlderThan(long tick) {
        synchronized (lock) {
            for (TreeMap<Long, InputCommand> q : queues.values()) {
                while (!q.isEmpty() && q.firstKey() < tick) {
                    q.pollFirstEntry();
                }
            }
        }
    }

    public Set<Integer> knownSequences(int clientId) {
        synchronized (lock) {
            Set<Integer> s = seenSequences.get(clientId);
            return s == null ? Set.of() : Set.copyOf(s);
        }
    }

    public int queuedCount(int clientId) {
        synchronized (lock) {
            TreeMap<Long, InputCommand> q = queues.get(clientId);
            return q == null ? 0 : q.size();
        }
    }
}
