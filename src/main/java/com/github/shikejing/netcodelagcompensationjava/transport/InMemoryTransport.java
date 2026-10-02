package com.github.shikejing.netcodelagcompensationjava.transport;

import com.github.shikejing.netcodelagcompensationjava.simulation.DeterministicRng;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Entirely in-process, no-external-service loopback "network". A caller-supplied
 * deterministic RNG decides jitter / loss / duplication / reordering, so a
 * scenario run with the same seed delivers the exact same delivery sequence on
 * every run and after a restart. Each receiver has its own arrival queue keyed
 * by virtual arrival time; inverted arrival times produce out-of-order delivery
 * naturally, and one slow peer can never block another peer's queue.
 */
public final class InMemoryTransport {

    private final int minDelayMs;
    private final int jitterMs;
    private final double lossRate;
    private final double duplicateRate;
    private final int reorderWindowMs;

    private record Pending(Datagram datagram, long arrivalVirtualMs) {
    }

    private final Map<Integer, PriorityQueue<Pending>> inboxes = new HashMap<>();
    private final Object lock = new Object();

    public InMemoryTransport(long seed,
                             int minDelayMs,
                             int jitterMs,
                             double lossRate,
                             double duplicateRate,
                             int reorderWindowMs) {
        // The transport itself is stateless RNG-wise: randomness is injected on
        // every send() so a scenario owns one deterministic stream and a restart
        // reproduces the identical delivery schedule. The seed is retained so
        // wiring code can configure the shared RNG from a single place.
        this.minDelayMs = minDelayMs;
        this.jitterMs = jitterMs;
        this.lossRate = lossRate;
        this.duplicateRate = duplicateRate;
        this.reorderWindowMs = reorderWindowMs;
    }

    public void send(int senderId, int receiverId, long sendClientTimeMs,
                     byte[] payload, DeterministicRng rng) {
        if (rng.nextUnit() < lossRate) {
            return; // deterministic loss
        }
        long jitter = jitterMs <= 0 ? 0L : (long) (rng.nextUnit() * jitterMs);
        long baseDelay = minDelayMs + jitter;
        if (reorderWindowMs > 0 && rng.nextUnit() < 0.3) {
            // Hold this datagram inside the reorder window so later ones overtake it.
            baseDelay += reorderWindowMs;
        }
        Datagram d = new Datagram(senderId, receiverId, sendClientTimeMs, payload.clone());
        synchronized (lock) {
            inbox(receiverId).add(new Pending(d, sendClientTimeMs + baseDelay));
            if (rng.nextUnit() < duplicateRate) {
                long dupJitter = jitterMs <= 0 ? 0L : (long) (rng.nextUnit() * jitterMs);
                Datagram dup = new Datagram(senderId, receiverId, sendClientTimeMs, payload.clone());
                inbox(receiverId).add(new Pending(dup, sendClientTimeMs + minDelayMs + dupJitter));
            }
        }
    }

    private PriorityQueue<Pending> inbox(int receiverId) {
        return inboxes.computeIfAbsent(receiverId,
                k -> new PriorityQueue<>(Comparator.comparingLong(Pending::arrivalVirtualMs)));
    }

    /** Return (and remove) every datagram due by {@code nowVirtualMs}, in arrival order. */
    public List<Datagram> deliver(int receiverId, long nowVirtualMs) {
        List<Datagram> out = new ArrayList<>();
        synchronized (lock) {
            PriorityQueue<Pending> q = inboxes.get(receiverId);
            if (q == null) {
                return out;
            }
            while (!q.isEmpty() && q.peek().arrivalVirtualMs() <= nowVirtualMs) {
                out.add(q.poll().datagram());
            }
        }
        return out;
    }

    public int inFlightCount() {
        synchronized (lock) {
            int n = 0;
            for (PriorityQueue<Pending> q : inboxes.values()) {
                n += q.size();
            }
            return n;
        }
    }
}
