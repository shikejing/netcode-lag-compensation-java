package com.github.shikejing.netcodelagcompensationjava.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded, thread-safe append-only log of ingestion decisions. When the
 * capacity is reached the oldest entries are overwritten (ring semantics),
 * so auditing a recent correction never grows without bound.
 */
public final class AuditLog {

    private final int capacity;
    private final List<InputAudit> entries;
    private int head;

    public AuditLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.entries = new ArrayList<>(capacity);
    }

    public synchronized void append(InputAudit audit) {
        if (entries.size() < capacity) {
            entries.add(audit);
        } else {
            entries.set(head, audit);
            head = (head + 1) % capacity;
        }
    }

    public synchronized List<InputAudit> entries() {
        List<InputAudit> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            out.add(entries.get((head + i) % capacity));
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized List<InputAudit> forClient(int clientId) {
        return entries().stream()
                .filter(a -> a.command().clientId() == clientId)
                .toList();
    }

    public synchronized int size() {
        return entries.size();
    }
}
