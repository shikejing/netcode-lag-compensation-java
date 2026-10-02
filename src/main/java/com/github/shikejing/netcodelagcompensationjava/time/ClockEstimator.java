package com.github.shikejing.netcodelagcompensationjava.time;

/**
 * Estimates the offset between a client clock and the server clock from
 * timestamped ping samples (NTP-midpoint convention). Both RTT jitter and
 * offset jitter are exponentially smoothed. Clock adjustments never move the
 * *estimated server time* backwards: the mapping client->server is monotone
 * non-decreasing per client, so a client clock set backwards can never make a
 * replay/rewind timeline flow backwards or jump.
 */
public final class ClockEstimator {

    private final double rttAlpha;
    private final double offsetAlpha;

    private boolean primed;
    private long lastClientSendMs;
    private long lastClientReceiveMs;
    private double rttSmoothed;
    private double offsetSmoothed;
    private long adjustments;

    /** Largest server-time estimate ever handed out: the monotonic floor. */
    private long lastServerEstimate = Long.MIN_VALUE;

    public ClockEstimator(double rttSmoothingAlpha, double offsetSmoothingAlpha) {
        if (rttSmoothingAlpha <= 0.0 || rttSmoothingAlpha > 1.0
                || offsetSmoothingAlpha <= 0.0 || offsetSmoothingAlpha > 1.0) {
            throw new IllegalArgumentException("smoothing alphas must be within (0, 1]");
        }
        this.rttAlpha = rttSmoothingAlpha;
        this.offsetAlpha = offsetSmoothingAlpha;
    }

    /**
     * Feed one round-trip sample.
     *
     * @param clientSendMs    client clock when the ping left
     * @param serverReceiveMs server clock when the ping arrived
     * @param clientReceiveMs client clock when the pong came back
     */
    public synchronized ClockSnapshot sample(long clientSendMs, long serverReceiveMs, long clientReceiveMs) {
        long rttRaw = clientReceiveMs - clientSendMs;
        double clientMidpoint = clientSendMs + (rttRaw / 2.0);
        long offsetInstant = Math.round(serverReceiveMs - clientMidpoint);

        if (!primed) {
            primed = true;
            rttSmoothed = rttRaw;
            offsetSmoothed = offsetInstant;
        } else {
            double oldOffset = offsetSmoothed;
            rttSmoothed += rttAlpha * (rttRaw - rttSmoothed);
            offsetSmoothed += offsetAlpha * (offsetInstant - offsetSmoothed);
            // A meaningful clock adjustment: the offset moved by more than a quarter of
            // the smoothed RTT, or the client timestamps themselves stepped backwards.
            boolean largeOffsetMove =
                    Math.abs(offsetInstant - oldOffset) > Math.max(1.0, rttSmoothed / 4.0);
            boolean clientWentBackwards =
                    clientSendMs < lastClientSendMs || clientReceiveMs < lastClientReceiveMs;
            if (largeOffsetMove || clientWentBackwards) {
                adjustments++;
            }
        }

        lastClientSendMs = clientSendMs;
        lastClientReceiveMs = clientReceiveMs;
        // Return a pure (non-floor-mutating) view; taking a sample must not move
        // the client->server mapping floor used for inputs/shots.
        return computeSnapshot(clientReceiveMs, false);
    }

    /**
     * Map a client timestamp onto the estimated server timeline.
     * The result is clamped to be non-decreasing across calls.
     */
    public synchronized long toServerTimeMs(long clientTimeMs) {
        ClockSnapshot snap = computeSnapshot(clientTimeMs, true);
        return snap.serverTimeEstimateMs();
    }

    /** Pure view at a client time; does not move the monotone floor. */
    public synchronized ClockSnapshot snapshot(long clientTimeMs) {
        return computeSnapshot(clientTimeMs, false);
    }

    private ClockSnapshot computeSnapshot(long clientTimeMs, boolean advanceFloor) {
        long raw = Math.round(clientTimeMs + offsetSmoothed);
        boolean clamped = false;
        long mapped = raw;
        if (primed && mapped < lastServerEstimate) {
            mapped = lastServerEstimate;
            clamped = true;
        }
        if (advanceFloor && mapped > lastServerEstimate) {
            lastServerEstimate = mapped;
        }
        long rttRawMs = primed ? (lastClientReceiveMs - lastClientSendMs) : 0L;
        return new ClockSnapshot(clientTimeMs, mapped, rttRawMs,
                rttSmoothed, Math.round(offsetSmoothed), adjustments, clamped);
    }
}
