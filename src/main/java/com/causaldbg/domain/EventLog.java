package com.causaldbg.domain;

import java.time.Instant;

/**
 * An append-only event log. Events are never rewritten; a later ingest
 * creates a new log whose id is greater and from which new sessions derive.
 */
public record EventLog(
        long id,
        String name,
        String fingerprint,
        int eventCount,
        Instant createdAt
) {
}
