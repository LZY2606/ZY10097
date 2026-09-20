package com.example.causalworkbench.service;

import java.util.List;
import java.util.Map;

public final class ReplayModels {
    private ReplayModels() {
    }

    public record RuleDefinition(String initialState, Map<String, String> transitions) {
    }

    public record WorkbenchRules(String initialState, Map<String, RuleDefinition> eventTypes) {
    }

    public record EventNode(
            String id,
            String serviceName,
            long seqNo,
            String originalKey,
            String effectiveKey,
            String eventType,
            String payloadJson,
            String parentId,
            Long wallClockMillis,
            String receiptId,
            String contentHash,
            boolean placeholder,
            String placeholderForEventId,
            List<String> duplicateReceiptIds,
            List<String> warnings
    ) {
    }

    public record Hypothesis(
            boolean ignoreDuplicates,
            boolean fillMissingParents,
            Map<String, String> keyAdjustments,
            List<String> seedOrder,
            Integer interleavingBound
    ) {
    }

    public record ReplayRequest(
            WorkbenchRules rules,
            List<EventNode> events,
            Hypothesis hypothesis
    ) {
    }

    public record Diagnosis(
            String code,
            String severity,
            String eventId,
            String aggregateKey,
            String message,
            List<String> evidenceIds
    ) {
    }

    public record SliceNode(String eventId, String reason) {
    }

    public record AggregateState(
            String aggregateKey,
            String state,
            String lastEventId,
            List<String> eventIds
    ) {
    }

    public record Linearization(
            List<String> eventIds,
            String outcome,
            String failedEventId,
            Integer failedRank,
            String failureCode,
            String failureMessage
    ) {
    }

    public record PairAnalysis(
            String leftEventId,
            String rightEventId,
            boolean concurrent,
            boolean commutes,
            String effect
    ) {
    }

    public record ReplayResult(
            String outcome,
            String earliestFailureCode,
            String earliestFailureEventId,
            Integer earliestFailureRank,
            String earliestFailureMessage,
            List<String> deterministicOrder,
            String deterministicSeed,
            List<Diagnosis> diagnoses,
            List<SliceNode> minimalSlice,
            List<AggregateState> aggregateStates,
            List<PairAnalysis> concurrentPairs,
            List<Linearization> linearizations,
            boolean boundReached,
            int exploredCount,
            Integer bound
    ) {
    }
}
