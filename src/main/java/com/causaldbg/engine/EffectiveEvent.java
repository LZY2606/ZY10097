package com.causaldbg.engine;

import com.causaldbg.domain.RawEvent;

import java.time.Instant;

/**
 * An event that participates in a replay: either raw evidence or a
 * synthetic placeholder. Placeholders are never business events: they
 * order the graph but do not drive aggregate state transitions.
 */
public record EffectiveEvent(
        String uid,
        String service,
        long seq,
        String key,
        String parentUid,
        String type,
        Instant wallClock,
        long order,
        boolean placeholder,
        String sourceRawUid,
        RawEvent raw
) {
    public static EffectiveEvent ofRaw(RawEvent event, String effectiveKey) {
        return new EffectiveEvent(
                event.eventUid(), event.service(), event.seq(), effectiveKey,
                event.parentUid(), event.type(), event.wallClock(), event.receivedIndex(),
                false, event.eventUid(), event);
    }

    public static EffectiveEvent placeholder(String uid, String service, Long seq,
                                             String key, String missingParentUid, long order) {
        return new EffectiveEvent(uid, service, seq == null ? -1L : seq, key,
                null, "PLACEHOLDER:" + missingParentUid, null, order,
                true, null, null);
    }
}
