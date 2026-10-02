package com.github.shikejing.netcodelagcompensationjava.input;

/**
 * What happened to an input when the server ingested it.
 */
public enum InputDisposition {
    /** First time seen, queued for its target tick. */
    ACCEPTED,
    /** Duplicate (same clientId + sequence); never applied twice. */
    DUPLICATE,
    /** Targets a tick whose authoritative result was already published; history is not rewritten. */
    LATE_REJECTED,
    /** Targets too far in the future beyond the configured lookahead bound. */
    TOO_FAR_FUTURE,
    /** Client backlog overflow; slow client is isolated, others keep advancing. */
    BACKLOG_FULL
}
