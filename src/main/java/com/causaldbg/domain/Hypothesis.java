package com.causaldbg.domain;

import java.util.List;

/**
 * A replay hypothesis: proposed repairs that produce derived state without
 * touching raw evidence.
 *
 * <ul>
 *   <li>IGNORE_DELIVERY  - treat a duplicate / seq-collision delivery as absent</li>
 *   <li>PLACEHOLDER_PARENT - insert a non-business placeholder for a missing parent</li>
 *   <li>REMAPPED_KEY - treat an event's correlation key as different for grouping</li>
 * </ul>
 * Placeholders are flagged synthetic and never count as real business events.
 */
public record Hypothesis(
        List<String> ignoreEventUids,
        List<PlaceholderRequest> placeholders,
        List<KeyRemap> remappedKeys,
        String notes
) {
    public record PlaceholderRequest(String missingParentUid, String key, String service, Long expectedSeq) {
    }

    public record KeyRemap(String eventUid, String newKey) {
    }

    public static Hypothesis empty() {
        return new Hypothesis(List.of(), List.of(), List.of(), null);
    }
}
