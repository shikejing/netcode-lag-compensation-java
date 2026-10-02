package com.github.shikejing.netcodelagcompensationjava.input;

import com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 单个玩家的输入队列。线程安全：输入线程并发 submit，服务端固定步长线
 * 程在每个 tick 调用 pollDue。
 *
 * 规则：
 *  - 重复 sequence 只接受一次（DUPLICATE），绝不重复应用；
 *  - sequence 大于已发布进度的输入进入缓冲（乱序到达不影响）；
 *  - 映射时刻早于当前 tick 的输入属于迟到输入（LATE），不会改写任何已发布结果；
 *  - 所有判定都有计数与最近记录，修正前后差异可解释、可追溯。
 */
public class InputQueue {

    public record InputDecision(InputOutcome outcome, long sequence, String detail) {
        public static InputDecision of(InputOutcome outcome, long sequence, String detail) {
            return new InputDecision(outcome, sequence, detail);
        }
    }

    public record LateRecord(long sequence, long clientTimeNanos, long mappedTick, long atTick, String reason) { }

    private final String playerId;
    private final TreeMap<Long, PlayerInput> pending = new TreeMap<>();
    private final Set<Long> appliedSeqs = new HashSet<>();
    private final Set<Long> evaluatedFireSeqs = new HashSet<>();

    private long lastAppliedSeq = 0L;
    private long lastSubmittedSeq = 0L;

    private long duplicateCount = 0L;
    private long lateCount = 0L;
    private long outOfOrderCount = 0L;
    private long supersededCount = 0L;
    private final List<LateRecord> lateRecords = new ArrayList<>();
    private static final int MAX_RECORDS = 64;

    public InputQueue(String playerId) {
        this.playerId = playerId;
    }

    public String playerId() { return playerId; }

    public synchronized InputDecision submit(PlayerInput input) {
        long seq = input.sequence();
        if (appliedSeqs.contains(seq) || pending.containsKey(seq)) {
            duplicateCount++;
            return InputDecision.of(InputOutcome.DUPLICATE, seq,
                    "sequence " + seq + " already seen for player " + playerId);
        }
        if (seq <= lastAppliedSeq) {
            // 理论上 appliedSeqs 已覆盖，双保险
            lateCount++;
            addRecord(new LateRecord(seq, input.clientTimeNanos(), -1L, -1L,
                    "sequence " + seq + " already applied for player " + playerId));
            return InputDecision.of(InputOutcome.LATE_ALREADY_PUBLISHED, seq,
                    "sequence " + seq + " already applied");
        }
        if (seq < lastSubmittedSeq) {
            outOfOrderCount++;
        }
        lastSubmittedSeq = Math.max(lastSubmittedSeq, seq);
        pending.put(seq, input);
        return InputDecision.of(InputOutcome.BUFFERED_OUT_OF_ORDER, seq,
                "buffered, pending count=" + pending.size());
    }

    /**
     * 在服务端 tick 时刻，取出所有映射到该 tick 的输入（按 sequence 排序）。
     * 映射时刻早于该 tick 的输入直接判定迟到并丢弃，绝不回改已发布快照。
     */
    public synchronized List<PlayerInput> pollDue(long tick, ClockEstimator clock, int tickRate) {
        List<PlayerInput> due = new ArrayList<>();
        Iterator<Map.Entry<Long, PlayerInput>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, PlayerInput> e = it.next();
            PlayerInput in = e.getValue();
            long mapped = clock.targetTick(in.clientTimeNanos(), tickRate);
            if (mapped < tick) {
                lateCount++;
                addRecord(new LateRecord(in.sequence(), in.clientTimeNanos(), mapped, tick,
                        "input maps to tick " + mapped + " which is already published at tick " + tick));
                it.remove();
            } else if (mapped == tick) {
                due.add(in);
                it.remove();
            }
            // mapped > tick: 未来输入，继续等待
        }
        due.sort(Comparator.comparingLong(PlayerInput::sequence));
        for (PlayerInput in : due) {
            appliedSeqs.add(in.sequence());
            lastAppliedSeq = Math.max(lastAppliedSeq, in.sequence());
        }
        return due;
    }

    /** 同一 tick 映射到多个输入时，保留最新 sequence，其余标记为被取代（不应用）。 */
    public synchronized PlayerInput pickNewestOrSupersede(List<PlayerInput> due) {
        if (due.isEmpty()) return null;
        PlayerInput newest = due.get(due.size() - 1);
        for (int i = 0; i < due.size() - 1; i++) {
            supersededCount++;
            PlayerInput old = due.get(i);
            addRecord(new LateRecord(old.sequence(), old.clientTimeNanos(), -1L, -1L,
                    "superseded by newer sequence " + newest.sequence() + " at same tick"));
        }
        return newest;
    }

    private void addRecord(LateRecord r) {
        if (lateRecords.size() >= MAX_RECORDS) {
            lateRecords.remove(0);
        }
        lateRecords.add(r);
    }

    /**
     * 取出所有尚未判定、且映射时刻不晚于当前 tick 的开火输入（按 sequence 排序）。
     * 未来开火输入（映射 tick > 当前 tick）继续等待。
     * 开火只判定一次（evaluatedFireSeqs 去重），迟到开火也会被判定（回退到过去时刻）。
     */
    public synchronized List<PlayerInput> dueFireInputs(long tick, ClockEstimator clock, int tickRate) {
        List<PlayerInput> fires = new ArrayList<>();
        Iterator<Map.Entry<Long, PlayerInput>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, PlayerInput> e = it.next();
            PlayerInput in = e.getValue();
            if (!in.fire()) continue;
            if (evaluatedFireSeqs.contains(in.sequence())) continue;
            long mapped = clock.mapToServerTick(in.clientTimeNanos(), tick, tickRate);
            if (mapped > tick) continue; // 未来开火，等待
            evaluatedFireSeqs.add(in.sequence());
            fires.add(in);
        }
        fires.sort(Comparator.comparingLong(PlayerInput::sequence));
        return fires;
    }

    public synchronized long lastAppliedSequence() { return lastAppliedSeq; }
    public synchronized long pendingCount() { return pending.size(); }
    public synchronized long duplicateCount() { return duplicateCount; }
    public synchronized long lateCount() { return lateCount; }
    public synchronized long outOfOrderCount() { return outOfOrderCount; }
    public synchronized long supersededCount() { return supersededCount; }
    public synchronized List<LateRecord> lateRecords() { return List.copyOf(lateRecords); }
}
