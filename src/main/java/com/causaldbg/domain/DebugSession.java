package com.causaldbg.domain;

import java.time.Instant;

/**
 * A saved debug session bound to an immutable (log fingerprint, rule version)
 * pair and to a hypothesis. Updated with optimistic versioning: two browsers
 * editing the same old version produce a 409 for the later writer.
 */
public record DebugSession(
        Long id,
        String name,
        long logId,
        String logFingerprint,
        String ruleVersion,
        String ruleJson,
        Hypothesis hypothesis,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        boolean invalidated,
        String invalidationReason
) {
}
