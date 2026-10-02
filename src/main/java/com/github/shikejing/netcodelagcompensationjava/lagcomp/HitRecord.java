package com.github.shikejing.netcodelagcompensationjava.lagcomp;

public record HitRecord(
        long id,
        long serverTick,
        long rewindTick,
        long clientTimeNanos,
        String shooterId,
        String victimId,
        HitStatus status,
        double originX,
        double originY,
        double aimX,
        double aimY,
        String reason
) { }
