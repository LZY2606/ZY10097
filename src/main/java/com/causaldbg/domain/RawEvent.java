package com.causaldbg.domain;

import java.time.Instant;

/**
 * Immutable representation of an event record exactly as received.
 * Raw evidence is never mutated in place; every derived artifact references
 * these rows by id and carries source fingerprints.
 */
public record RawEvent(
        long id,
        long logId,
        String eventUid,
        String service,
        long seq,
        String key,
        String parentUid,
        String type,
        String payloadHash,
        Instant wallClock,
        long receivedIndex,
        boolean tombstone
) {
    public String serviceSeq() {
        return service + "#" + seq;
    }
}
