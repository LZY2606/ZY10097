package com.causaldbg.engine;

import com.causaldbg.domain.Hypothesis;
import com.causaldbg.domain.RawEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns immutable raw rows + a hypothesis into the effective event set and
 * reports ingestion anomalies. Nothing here mutates the input log.
 */
public final class EventSetBuilder {

    private EventSetBuilder() {
    }

    public record Result(List<EffectiveEvent> events, List<Anomaly> anomalies,
                         Set<String> unresolvedParents, Set<String> placeholderParents,
                         Set<String> settledPlaceholderParents,
                         List<String> ignoredEventUids) {
    }

    public static Result build(List<RawEvent> raw, Hypothesis hypothesis) {
        List<RawEvent> ordered = new ArrayList<>(raw);
        ordered.sort(Comparator.comparingLong(RawEvent::receivedIndex));

        Set<String> ignore = hypothesis == null ? Set.of()
                : new HashSet<>(nullSafe(hypothesis.ignoreEventUids()));
        Map<String, String> remap = new HashMap<>();
        if (hypothesis != null && hypothesis.remappedKeys() != null) {
            for (Hypothesis.KeyRemap remapping : hypothesis.remappedKeys()) {
                remap.put(remapping.eventUid(), remapping.newKey());
            }
        }

        List<Anomaly> anomalies = new ArrayList<>();
        detectAnomalies(ordered, anomalies);

        Map<String, RawEvent> byUid = new LinkedHashMap<>();
        for (RawEvent event : ordered) {
            byUid.putIfAbsent(event.eventUid(), event);
        }

        Set<String> unresolved = new HashSet<>();
        List<EffectiveEvent> effective = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (RawEvent event : ordered) {
            if (ignore.contains(event.eventUid()) || !emitted.add(event.eventUid())) {
                continue;
            }
            String effectiveKey = remap.getOrDefault(event.eventUid(), event.key());
            effective.add(EffectiveEvent.ofRaw(event, effectiveKey));
        }

        Set<String> knownUids = new HashSet<>();
        for (EffectiveEvent event : effective) {
            knownUids.add(event.uid());
        }

        Set<String> placeholderParents = new HashSet<>();
        Set<String> settledParents = new HashSet<>();
        long placeholderOrder = 10_000_000L;
        List<EffectiveEvent> placeholders = new ArrayList<>();
        if (hypothesis != null && hypothesis.placeholders() != null) {
            for (Hypothesis.PlaceholderRequest request : hypothesis.placeholders()) {
                String parentUid = request.missingParentUid();
                if (byUid.containsKey(parentUid)) {
                    // The real business event referenced by an old placeholder
                    // hypothesis has since arrived in the evidence.
                    settledParents.add(parentUid);
                    continue;
                }
                if (knownUids.contains(parentUid) || placeholderParents.contains(parentUid)) {
                    continue;
                }
                EffectiveEvent synthetic = EffectiveEvent.placeholder(
                        parentUid, request.service() == null ? "?" : request.service(),
                        request.expectedSeq(), request.key(), parentUid, placeholderOrder++);
                placeholders.add(synthetic);
                placeholderParents.add(parentUid);
                knownUids.add(parentUid);
            }
        }

        for (EffectiveEvent event : effective) {
            if (event.parentUid() != null && !event.parentUid().isBlank()
                    && !knownUids.contains(event.parentUid())) {
                unresolved.add(event.parentUid());
            }
        }

        List<EffectiveEvent> all = new ArrayList<>(effective);
        all.addAll(placeholders);
        all.sort(Comparator.comparingLong(EffectiveEvent::order));
        return new Result(all, anomalies, unresolved, placeholderParents, settledParents, new ArrayList<>(ignore));
    }

    private static void detectAnomalies(List<RawEvent> ordered, List<Anomaly> anomalies) {
        Map<String, RawEvent> firstByUid = new HashMap<>();
        Map<String, Long> maxSeqSeenByService = new HashMap<>();

        for (RawEvent event : ordered) {
            RawEvent first = firstByUid.get(event.eventUid());
            if (first != null) {
                if (sameContent(first, event)) {
                    anomalies.add(new Anomaly(Anomaly.Kind.DUPLICATE_DELIVERY,
                            event.eventUid(), event.serviceSeq(),
                            "identical redelivery of " + event.eventUid()
                                    + " (received index " + first.receivedIndex()
                                    + " and " + event.receivedIndex() + ")"));
                } else {
                    anomalies.add(new Anomaly(Anomaly.Kind.SEQ_CONTENT_CONFLICT,
                            event.eventUid(), event.serviceSeq(),
                            "same service/seq identity " + event.serviceSeq()
                                    + " arrived with different content/type"));
                }
            } else {
                firstByUid.put(event.eventUid(), event);
            }

            long max = maxSeqSeenByService.getOrDefault(event.service(), Long.MIN_VALUE);
            if (event.seq() < max) {
                anomalies.add(new Anomaly(Anomaly.Kind.LATE_DELIVERY,
                        event.eventUid(), event.serviceSeq(),
                        "out-of-order arrival: seq " + event.seq()
                                + " arrived after seq " + max + " from service " + event.service()));
            }
            maxSeqSeenByService.put(event.service(), Math.max(max, event.seq()));
        }

        Map<String, RawEvent> byUid = new LinkedHashMap<>();
        for (RawEvent event : ordered) {
            byUid.putIfAbsent(event.eventUid(), event);
        }
        for (RawEvent event : ordered) {
            if (event.parentUid() != null && !event.parentUid().isBlank()
                    && !byUid.containsKey(event.parentUid())) {
                anomalies.add(new Anomaly(Anomaly.Kind.MISSING_PARENT,
                        event.eventUid(), event.serviceSeq(),
                        "causal parent " + event.parentUid() + " is absent from the log"));
            }
        }
    }

    private static boolean sameContent(RawEvent a, RawEvent b) {
        return java.util.Objects.equals(a.type(), b.type())
                && java.util.Objects.equals(a.payloadHash(), b.payloadHash())
                && java.util.Objects.equals(a.key(), b.key())
                && java.util.Objects.equals(a.parentUid(), b.parentUid())
                && a.seq() == b.seq();
    }

    private static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
