package com.github.shikejing.netcodelagcompensationjava.snapshot;

public record EntityState(
        String playerId,
        double x,
        double y,
        double aimX,
        double aimY,
        int health,
        boolean alive
) {
    public static EntityState alive(String playerId, double x, double y, double aimX, double aimY, int health) {
        return new EntityState(playerId, x, y, aimX, aimY, health, true);
    }
}
