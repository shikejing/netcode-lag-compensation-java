package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.simulation.DeterministicRng;
import com.github.shikejing.netcodelagcompensationjava.transport.Datagram;
import com.github.shikejing.netcodelagcompensationjava.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-process transport reproduces the exact same loss/duplicate/reorder
 * schedule for a fixed seed, and exercises those phenomena.
 */
class InMemoryTransportTest {

    private List<Datagram> runScenario(long seed) {
        DeterministicRng rng = new DeterministicRng(seed);
        InMemoryTransport transport = new InMemoryTransport(seed,
                10, 40, 0.2, 0.2, 30);
        for (int i = 0; i < 200; i++) {
            transport.send(1, 2, i * 10L, ("m" + i).getBytes(), rng);
        }
        List<Datagram> delivered = new ArrayList<>();
        for (int t = 0; t <= 2500; t += 5) {
            delivered.addAll(transport.deliver(2, t));
        }
        return delivered;
    }

    @Test
    void sameSeedYieldsIdenticalDeliverySequence() {
        List<Datagram> a = runScenario(12345L);
        List<Datagram> b = runScenario(12345L);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).sentClientTimeMs(), b.get(i).sentClientTimeMs());
            assertEquals(new String(a.get(i).payload()), new String(b.get(i).payload()));
        }
        List<Datagram> c = runScenario(999L);
        // Different seed is not guaranteed but overwhelmingly likely to differ.
        assertTrue(a.size() != c.size() || !sameOrder(a, c));
    }

    private boolean sameOrder(List<Datagram> a, List<Datagram> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).sentClientTimeMs() != b.get(i).sentClientTimeMs()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void scenarioContainsLossDuplicatesAndReordering() {
        List<Datagram> delivered = runScenario(777L);
        int sent = 200;
        // Loss: not everything arrived.
        long uniqueMessages = delivered.stream()
                .map(d -> d.sentClientTimeMs()).distinct().count();
        assertTrue(uniqueMessages < sent, "some datagrams should be lost, got " + uniqueMessages);

        // Duplicates: some timestamps appear more than once.
        long duplicateDeliveries = delivered.stream()
                .mapToLong(d -> d.sentClientTimeMs())
                .filter(ts -> delivered.stream().filter(x -> x.sentClientTimeMs() == ts).count() > 1)
                .distinct().count();
        assertTrue(duplicateDeliveries > 0, "duplicate deliveries should be present");

        // Reordering: the delivered sequence is not monotonic in send time.
        boolean reordered = false;
        for (int i = 1; i < delivered.size(); i++) {
            if (delivered.get(i).sentClientTimeMs() < delivered.get(i - 1).sentClientTimeMs()) {
                reordered = true;
                break;
            }
        }
        assertTrue(reordered, "out-of-order delivery should be present");
    }

    @Test
    void otherReceiversInboxIsIsolated() {
        DeterministicRng rng = new DeterministicRng(1L);
        InMemoryTransport transport = new InMemoryTransport(1L, 5, 0, 0.0, 0.0, 0);
        transport.send(1, 2, 0L, "x".getBytes(), rng);
        assertTrue(transport.deliver(3, 1000L).isEmpty());
        assertEquals(1, transport.deliver(2, 1000L).size());
    }
}
