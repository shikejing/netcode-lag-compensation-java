package com.github.shikejing.netcodelagcompensationjava.input;

public record PlayerInput(
        long sequence,
        long clientTimeNanos,
        int moveX,
        int moveY,
        boolean fire
) {
    public static PlayerInput of(long sequence, long clientTimeNanos, int moveX, int moveY, boolean fire) {
        return new PlayerInput(sequence, clientTimeNanos, moveX, moveY, fire);
    }
}
