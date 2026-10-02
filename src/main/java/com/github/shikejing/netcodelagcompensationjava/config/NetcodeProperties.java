package com.github.shikejing.netcodelagcompensationjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "netcode")
public class NetcodeProperties {
    private int tickRate = 60;
    private int maxRewindTicks = 12;
    private int snapshotHistoryTicks = 256;
    private int interpolationDelayTicks = 2;
    private long fixedSeed = 42L;
    private double clockSmoothingAlpha = 0.1;
    private double clockClampNanos = 250_000_000L;
    private Network network = new Network();
    private String journalPath = null;

    public static class Network {
        private long baseDelayNanos = 5_000_000L;
        private long jitterNanos = 15_000_000L;
        private double lossRate = 0.05;
        private double duplicateRate = 0.02;
        private long seed = 7L;

        public long getBaseDelayNanos() { return baseDelayNanos; }
        public void setBaseDelayNanos(long baseDelayNanos) { this.baseDelayNanos = baseDelayNanos; }
        public long getJitterNanos() { return jitterNanos; }
        public void setJitterNanos(long jitterNanos) { this.jitterNanos = jitterNanos; }
        public double getLossRate() { return lossRate; }
        public void setLossRate(double lossRate) { this.lossRate = lossRate; }
        public double getDuplicateRate() { return duplicateRate; }
        public void setDuplicateRate(double duplicateRate) { this.duplicateRate = duplicateRate; }
        public long getSeed() { return seed; }
        public void setSeed(long seed) { this.seed = seed; }
    }

    public int getTickRate() { return tickRate; }
    public void setTickRate(int tickRate) { this.tickRate = tickRate; }
    public int getMaxRewindTicks() { return maxRewindTicks; }
    public void setMaxRewindTicks(int maxRewindTicks) { this.maxRewindTicks = maxRewindTicks; }
    public int getSnapshotHistoryTicks() { return snapshotHistoryTicks; }
    public void setSnapshotHistoryTicks(int snapshotHistoryTicks) { this.snapshotHistoryTicks = snapshotHistoryTicks; }
    public int getInterpolationDelayTicks() { return interpolationDelayTicks; }
    public void setInterpolationDelayTicks(int interpolationDelayTicks) { this.interpolationDelayTicks = interpolationDelayTicks; }
    public long getFixedSeed() { return fixedSeed; }
    public void setFixedSeed(long fixedSeed) { this.fixedSeed = fixedSeed; }
    public double getClockSmoothingAlpha() { return clockSmoothingAlpha; }
    public void setClockSmoothingAlpha(double clockSmoothingAlpha) { this.clockSmoothingAlpha = clockSmoothingAlpha; }
    public double getClockClampNanos() { return clockClampNanos; }
    public void setClockClampNanos(double clockClampNanos) { this.clockClampNanos = clockClampNanos; }
    public Network getNetwork() { return network; }
    public void setNetwork(Network network) { this.network = network; }
    public String getJournalPath() { return journalPath; }
    public void setJournalPath(String journalPath) { this.journalPath = journalPath; }
}
