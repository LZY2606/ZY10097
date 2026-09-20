package com.causaldbg.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Outcome of replaying one hypothesis under a fixed or enumerated ordering. */
public record ReplayResult(
        String topoSeedDescription,
        List<String> chosenOrder,
        Map<String, AggregateTrace> aggregates,
        FailureSummary earliestFailure,
        MinimalSlice minimalSlice,
        List<ConcurrentPair> concurrentPairs,
        Set<String> unresolvedParents,
        Set<String> placeholderParents,
        Set<String> settledPlaceholderParents,
        Set<String> cyclicKeys,
        boolean placeholderStillOpen,
        InterleavingReport interleavings
) {
    public record AggregateTrace(
            String key,
            String initialState,
            String currentState,
            List<Step> steps
    ) {
    }

    public record Step(
            int index,
            String eventUid,
            String type,
            String fromState,
            String toState,
            boolean applied,
            boolean placeholder,
            String note
    ) {
    }

    public record FailureSummary(
            String aggregateKey,
            String eventUid,
            String eventType,
            String fromState,
            String message
    ) {
    }

    public record MinimalSlice(
            String aggregateKey,
            String failedEventUid,
            List<String> causalEventUids,
            List<SliceStep> steps,
            String explanation
    ) {
    }

    public record SliceStep(int index, String eventUid, String type,
                            String fromState, String toState, boolean placeholder) {
    }

    /**
     * @param canSwapConcurrency events are concurrent, so either relative order is legal
     * @param changesOutcome     swapping the relative order changes the first-failure conclusion
     */
    public record ConcurrentPair(
            String eventA, String eventB,
            boolean canSwapConcurrency,
            boolean changesOutcome,
            String note
    ) {
    }

    public record InterleavingReport(
            int requestedBound,
            int enumerated,
            boolean truncatedUnknown,
            List<OrderingOutcome> outcomes
    ) {
    }

    public record OrderingOutcome(List<String> order, boolean failed,
                                  String failedEventUid, String aggregateKey) {
    }
}
