package com.github.shikejing.netcodelagcompensationjava.transport;

/**
 * One datagram exchanged over the in-process loopback transport.
 */
public record Datagram(
        int senderId,
        int receiverId,
        long sentClientTimeMs,
        byte[] payload
) {
}
