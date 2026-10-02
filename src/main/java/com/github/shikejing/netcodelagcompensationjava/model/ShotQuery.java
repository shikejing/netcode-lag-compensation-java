package com.github.shikejing.netcodelagcompensationjava.model;

/**
 * A hitscan shot request. {@code clientTimeMs} is the shooter's input timestamp
 * on its own clock; the server maps it onto the server timeline before rewinding.
 */
public record ShotQuery(
        int shooterId,
        long clientTimeMs,
        Vec2 origin,
        Vec2 direction
) {
}
