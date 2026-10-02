package com.github.shikejing.netcodelagcompensationjava.simulation;

/**
 * Deterministic 64-bit linear congruential generator (Numerical Recipes constants).
 * Its state is carried inside every world snapshot so replay is byte-for-byte stable
 * with the same seed and inputs, including after a restart from saved state.
 */
public final class DeterministicRng {

    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;

    private long state;

    public DeterministicRng(long seed) {
        // Mix the seed so small seed differences still decorrelate streams.
        this.state = mix(seed);
    }

    private DeterministicRng(long state, boolean direct) {
        this.state = state;
    }

    /** Reconstruct a generator from the state stored in a snapshot (restart / replay). */
    public static DeterministicRng fromState(long storedState) {
        return new DeterministicRng(storedState, true);
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long state() {
        return state;
    }

    public long nextLong() {
        state = state * MULTIPLIER + INCREMENT;
        return state;
    }

    /** Uniform double in [0, 1) using the upper 53 bits. */
    public double nextUnit() {
        return (nextLong() >>> 11) * (1.0 / (1L << 53));
    }
}
