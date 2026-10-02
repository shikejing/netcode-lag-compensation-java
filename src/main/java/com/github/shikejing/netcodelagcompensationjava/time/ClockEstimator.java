package com.github.shikejing.netcodelagcompensationjava.time;

/**
 * 客户端时钟估计器（每玩家一个）。
 *
 * 时间模型：
 *  - 客户端输入携带客户端时钟戳 t_c；服务端在收到时刻 t_s 采样，
 *    单向偏移样本 offset = t_s - t_c（假设上下行对称，RTT ≈ 2 * 单向偏移）。
 *  - 偏移用指数滑动平均（EMA）平滑，抖动定义为 |样本 - 平滑偏移| 的 EMA。
 *  - 为防止客户端时钟被调整/回拨导致回放时间轴倒流或跳变：
 *      1) 单样本对偏移的修正幅度有上限（clampDelta），避免一次性跳变；
 *      2) 客户端时间到服务端 tick 的映射单调不减（max(lastMapped, ...)），
 *         且不允许映射到当前 tick 之后（未来输入）或 0 之前。
 */
public class ClockEstimator {

    public record ClockEstimate(long offsetNanos, long jitterNanos, long rttNanos, long mappedTick) { }

    private final double alpha;
    private final long maxDeltaPerSampleNanos;

    private boolean initialized = false;
    private long offsetNanos = 0L;
    private long jitterNanos = 0L;
    private long lastMappedTick = 0L;
    private long sampleCount = 0L;

    public ClockEstimator() {
        this(0.1d, 250_000_000L);
    }

    public ClockEstimator(double alpha, long maxDeltaPerSampleNanos) {
        this.alpha = alpha;
        this.maxDeltaPerSampleNanos = maxDeltaPerSampleNanos;
    }

    public synchronized void addSample(long clientTimeNanos, long serverRecvNanos) {
        long sample = serverRecvNanos - clientTimeNanos;
        if (!initialized) {
            offsetNanos = sample;
            jitterNanos = 0L;
            initialized = true;
        } else {
            long delta = sample - offsetNanos;
            // 限制单次修正幅度：时钟跳变/回拨不允许一次性反映到时间轴上
            if (Math.abs(delta) > maxDeltaPerSampleNanos) {
                delta = delta > 0 ? maxDeltaPerSampleNanos : -maxDeltaPerSampleNanos;
            }
            long effectiveSample = offsetNanos + delta;
            offsetNanos = (long) Math.round(alpha * effectiveSample + (1 - alpha) * offsetNanos);
            long deviation = Math.abs(sample - offsetNanos);
            jitterNanos = (long) Math.round(alpha * deviation + (1 - alpha) * jitterNanos);
        }
        sampleCount++;
    }

    /**
     * 输入调度用：客户端输入的目标服务端 tick（不 clamp）。
     * 大于当前 tick 表示输入还在未来（应等待），小于当前 tick 表示迟到。
     */
    public synchronized long targetTick(long clientTimeNanos, int tickRate) {
        return Tick.nanosToTick(clientTimeNanos + offsetNanos, tickRate);
    }

    /**
     * 正向时间轴映射（渲染/插值用）：结果单调不减，落在 [0, currentTick]。
     * 时钟回拨/跳变时回放时间轴不倒流、不跨到未来。
     */
    public synchronized long mapToServerTick(long clientTimeNanos, long currentTick, int tickRate) {
        long raw = Tick.nanosToTick(clientTimeNanos + offsetNanos, tickRate);
        long clamped = Math.max(0L, Math.min(raw, currentTick));
        long mapped = Math.max(lastMappedTick, clamped);
        lastMappedTick = mapped;
        return mapped;
    }

    /**
     * 延迟补偿回退映射：允许回到过去时刻（射手输入时刻），
     * 但钳制在 [0, currentTick]。偏移经 EMA 平滑，时钟跳变不会一次性反映。
     * 回退窗口的上界由 LagCompensator 另行判定（TOO_LATE）。
     */
    public synchronized long rewindTick(long clientTimeNanos, long currentTick, int tickRate) {
        long raw = Tick.nanosToTick(clientTimeNanos + offsetNanos, tickRate);
        return Math.max(0L, Math.min(raw, currentTick));
    }

    public synchronized ClockEstimate estimate(long currentTick, int tickRate) {
        return new ClockEstimate(offsetNanos, jitterNanos, Math.abs(2 * offsetNanos), lastMappedTick);
    }

    public synchronized long sampleCount() { return sampleCount; }
}
