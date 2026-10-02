package com.github.shikejing.netcodelagcompensationjava.model;

/**
 * Immutable 2D vector used for positions and movement directions.
 */
public record Vec2(double x, double y) {

    public static final Vec2 ZERO = new Vec2(0.0, 0.0);

    public Vec2 add(Vec2 other) {
        return new Vec2(x + other.x, y + other.y);
    }

    public Vec2 subtract(Vec2 other) {
        return new Vec2(x - other.x, y - other.y);
    }

    public Vec2 scale(double s) {
        return new Vec2(x * s, y * s);
    }

    public double dot(Vec2 other) {
        return x * other.x + y * other.y;
    }

    public double lengthSquared() {
        return x * x + y * y;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public Vec2 normalized() {
        double len = length();
        if (len == 0.0) {
            return ZERO;
        }
        return new Vec2(x / len, y / len);
    }

    public static double distance(Vec2 a, Vec2 b) {
        return a.subtract(b).length();
    }

    public static Vec2 lerp(Vec2 a, Vec2 b, double t) {
        return new Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
    }
}
