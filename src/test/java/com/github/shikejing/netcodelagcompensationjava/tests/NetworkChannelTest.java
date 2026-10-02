package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.transport.InMemoryChannel;
import com.github.shikejing.netcodelagcompensationjava.transport.NetworkMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存管道：丢包、抖动、乱序、重复；固定种子行为可复现。
 */
class NetworkChannelTest {

    private static final long TN = 16_666_666L;

    private NetworkMessage inputMsg(long seq) {
        return new NetworkMessage.InputMessage("p1", new PlayerInput(seq, seq * TN, 1, 0, false));
    }

    @Test
    void fixedSeedIsReproducible() {
        InMemoryChannel ch1 = new InMemoryChannel(123L, 5_000_000L, 15_000_000L, 0.1d, 0.05d);
        InMemoryChannel ch2 = new InMemoryChannel(123L, 5_000_000L, 15_000_000L, 0.1d, 0.05d);
        for (long i = 0; i < 50; i++) {
            ch1.send(inputMsg(i), i * TN);
            ch2.send(inputMsg(i), i * TN);
        }
        // 投递时刻为 1 秒后，确保全部到期
        List<NetworkMessage> r1 = ch1.drainDue(1_000_000_000_000L);
        List<NetworkMessage> r2 = ch2.drainDue(1_000_000_000_000L);
        assertEquals(r1.size(), r2.size(), "same seed must produce same delivery count");
        for (int i = 0; i < r1.size(); i++) {
            assertEquals(r1.get(i), r2.get(i), "same seed must produce same messages in same order");
        }
    }

    @Test
    void zeroLossDeliversAll() {
        InMemoryChannel ch = new InMemoryChannel(1L, 5_000_000L, 0L, 0.0d, 0.0d);
        for (long i = 0; i < 20; i++) ch.send(inputMsg(i), i * TN);
        List<NetworkMessage> due = ch.drainDue(1_000_000_000_000L);
        assertEquals(20, due.size());
        assertEquals(0, ch.droppedCount());
    }

    @Test
    void lossRateDropsPackets() {
        InMemoryChannel ch = new InMemoryChannel(1L, 5_000_000L, 0L, 1.0d, 0.0d); // 100% 丢包
        for (long i = 0; i < 10; i++) ch.send(inputMsg(i), i * TN);
        List<NetworkMessage> due = ch.drainDue(1_000_000_000_000L);
        assertTrue(due.isEmpty());
        assertEquals(10, ch.droppedCount());
    }

    @Test
    void duplicateRateDuplicates() {
        InMemoryChannel ch = new InMemoryChannel(1L, 5_000_000L, 0L, 0.0d, 1.0d); // 100% 重复
        for (long i = 0; i < 10; i++) ch.send(inputMsg(i), i * TN);
        List<NetworkMessage> due = ch.drainDue(1_000_000_000_000L);
        assertEquals(20, due.size(), "all packets duplicated");
        assertEquals(10, ch.duplicatedCount());
    }

    @Test
    void jitterCausesReordering() {
        // 大抖动 + 零丢包：后发的包可能先到
        InMemoryChannel ch = new InMemoryChannel(99L, 0L, 50_000_000L, 0.0d, 0.0d);
        for (long i = 0; i < 20; i++) ch.send(inputMsg(i), i * TN);
        List<NetworkMessage> due = ch.drainDue(1_000_000_000_000L);
        assertEquals(20, due.size());
        // 验证存在乱序：至少有一个包的序号小于前一个
        boolean reordered = false;
        long prev = -1;
        for (NetworkMessage m : due) {
            long seq = ((NetworkMessage.InputMessage) m).input().sequence();
            if (seq < prev) { reordered = true; break; }
            prev = seq;
        }
        assertTrue(reordered, "jitter should cause reordering");
    }

    @Test
    void drainDueOnlyReturnsDueMessages() {
        InMemoryChannel ch = new InMemoryChannel(1L, 100_000_000L, 0L, 0.0d, 0.0d); // 100ms 延迟
        ch.send(inputMsg(1), 0L);
        // 50ms 时未到期
        List<NetworkMessage> early = ch.drainDue(50_000_000L);
        assertTrue(early.isEmpty());
        // 150ms 时到期
        List<NetworkMessage> late = ch.drainDue(150_000_000L);
        assertEquals(1, late.size());
    }
}
