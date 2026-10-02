package com.github.shikejing.netcodelagcompensationjava.transport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 内存管道（本地回环）。用可复现的随机模型模拟真实网络：
 *  - 丢包 lossRate；
 *  - 抖动：投递延迟 = baseDelay + [0, jitterNanos) 均匀随机；
 *  - 乱序：不同消息延迟不同，自然乱序到达；
 *  - 重复：duplicateRate 概率额外投递一份副本。
 * 固定种子时行为完全可复现。
 */
public class InMemoryChannel {

    private record Envelope(long deliverAtNanos, long seq, NetworkMessage message) { }

    private final Random rng;
    private final long baseDelayNanos;
    private final long jitterNanos;
    private final double lossRate;
    private final double duplicateRate;

    private final PriorityQueue<Envelope> queue =
            new PriorityQueue<>(Comparator.comparingLong(Envelope::deliverAtNanos).thenComparingLong(Envelope::seq));
    private long seqCounter = 0L;
    private long sentCount = 0L;
    private long droppedCount = 0L;
    private long duplicatedCount = 0L;

    public InMemoryChannel(long seed, long baseDelayNanos, long jitterNanos,
                           double lossRate, double duplicateRate) {
        this.rng = new Random(seed);
        this.baseDelayNanos = baseDelayNanos;
        this.jitterNanos = jitterNanos;
        this.lossRate = lossRate;
        this.duplicateRate = duplicateRate;
    }

    public synchronized void send(NetworkMessage message, long nowNanos) {
        sentCount++;
        if (rng.nextDouble() < lossRate) {
            droppedCount++;
            return;
        }
        schedule(message, nowNanos);
        if (rng.nextDouble() < duplicateRate) {
            duplicatedCount++;
            // 副本延迟独立采样，可能先到也可能后到
            schedule(message, nowNanos);
        }
    }

    private void schedule(NetworkMessage message, long nowNanos) {
        long jitter = jitterNanos <= 0 ? 0L : (long) (rng.nextDouble() * jitterNanos);
        long deliverAt = nowNanos + baseDelayNanos + jitter;
        queue.add(new Envelope(deliverAt, seqCounter++, message));
    }

    /** 取出所有到期消息（按投递时刻排序）。 */
    public synchronized List<NetworkMessage> drainDue(long nowNanos) {
        List<NetworkMessage> out = new ArrayList<>();
        while (!queue.isEmpty() && queue.peek().deliverAtNanos() <= nowNanos) {
            out.add(queue.poll().message());
        }
        return out;
    }

    public synchronized long sentCount() { return sentCount; }
    public synchronized long droppedCount() { return droppedCount; }
    public synchronized long duplicatedCount() { return duplicatedCount; }
    public synchronized long pendingCount() { return queue.size(); }
}
