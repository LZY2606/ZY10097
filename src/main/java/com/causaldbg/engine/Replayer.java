package com.causaldbg.engine;

import com.causaldbg.domain.RuleSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates aggregate state transitions along fixed or enumerated
 * linearizations of the causal partial order.
 *
 * <p>Placeholders never act as business events: they are skipped at apply
 * time and their presence marks the replay as provisional. A replay stops at
 * the first invariant violation per aggregate; the earliest violation across
 * aggregates becomes the reported failure.
 */
public final class Replayer {

    private static final int DEFAULT_INTERLEAVING_BOUND = 64;

    private Replayer() {
    }

    public static ReplayResult replay(Map<String, PartialOrder> graph, RuleSet rules) {
        return replay(graph, rules, null, DEFAULT_INTERLEAVING_BOUND, Set.of());
    }

    public static ReplayResult replay(Map<String, PartialOrder> graph, RuleSet rules,
                                      List<String> fixedOrder, int interleavingBound,
                                      Set<String> settledPlaceholderParents) {
        List<String> chosenOrder = new ArrayList<>();
        Map<String, List<String>> perAggregateOrder = new LinkedHashMap<>();
        Set<String> cyclicKeys = new HashSet<>();
        for (PartialOrder po : graph.values()) {
            if (po.hasCycle()) {
                cyclicKeys.add(po.key());
                continue;
            }
            List<String> localOrder = localOrderFor(po, fixedOrder);
            perAggregateOrder.put(po.key(), localOrder);
            chosenOrder.addAll(localOrder);
        }

        Map<String, AggregateRun> runs = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : perAggregateOrder.entrySet()) {
            PartialOrder po = graph.get(entry.getKey());
            AggregateRun run = new AggregateRun(po, rules);
            runs.put(entry.getKey(), run);
            for (String uid : entry.getValue()) {
                EffectiveEvent event = po.node(uid);
                if (event != null) {
                    run.apply(event);
                }
            }
        }

        Map<String, ReplayResult.AggregateTrace> traces = new LinkedHashMap<>();
        ReplayResult.FailureSummary earliest = null;
        for (Map.Entry<String, AggregateRun> entry : runs.entrySet()) {
            traces.put(entry.getKey(), entry.getValue().trace());
            ReplayResult.FailureSummary failure = entry.getValue().firstFailure();
            if (failure != null && (earliest == null
                    || failure.eventUid().compareTo(earliest.eventUid()) < 0
                    || (failure.eventUid().equals(earliest.eventUid())
                    && failure.aggregateKey().compareTo(earliest.aggregateKey()) < 0))) {
                earliest = failure;
            }
        }

        ReplayResult.MinimalSlice slice = null;
        List<ReplayResult.ConcurrentPair> pairReport = List.of();
        if (earliest != null) {
            PartialOrder failedGraph = graph.get(earliest.aggregateKey());
            AggregateRun failedRun = runs.get(earliest.aggregateKey());
            slice = buildMinimalSlice(failedGraph, failedRun, rules, earliest);
            pairReport = analyzeConcurrency(failedGraph, failedRun, rules, earliest);
        }

        Set<String> unresolved = new HashSet<>();
        Set<String> placeholders = new HashSet<>();
        Set<String> settled = new HashSet<>(settledPlaceholderParents);
        boolean openPlaceholder = false;
        for (PartialOrder po : graph.values()) {
            for (EffectiveEvent event : po.nodes()) {
                if (event.placeholder()) {
                    placeholders.add(event.uid());
                    openPlaceholder = true;
                }
            }
        }

        ReplayResult.InterleavingReport report =
                enumerateOutcomes(graph, rules, interleavingBound, earliest);

        return new ReplayResult(
                "canonical: Kahn-ready node with min uid (service,seq tie-break)",
                chosenOrder, traces, earliest, slice, pairReport,
                unresolved, placeholders, settled, cyclicKeys, openPlaceholder, report);
    }

    private static List<String> localOrderFor(PartialOrder po, List<String> fixedOrder) {
        java.util.Set<String> members = new java.util.HashSet<>();
        for (EffectiveEvent event : po.nodes()) {
            members.add(event.uid());
        }
        if (fixedOrder == null || fixedOrder.isEmpty()) {
            return po.canonicalTopoSeed();
        }
        List<String> filtered = new ArrayList<>();
        for (String uid : fixedOrder) {
            if (members.contains(uid)) {
                filtered.add(uid);
            }
        }
        if (filtered.size() != members.size()) {
            List<String> missing = new ArrayList<>(members);
            missing.removeAll(filtered);
            throw new IllegalArgumentException("fixed order for aggregate " + po.key()
                    + " is missing events: " + missing);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String uid : filtered) {
            for (EffectiveEvent event : po.nodes()) {
                if (event.uid().equals(uid) && event.parentUid() != null
                        && members.contains(event.parentUid())
                        && !seen.contains(event.parentUid())) {
                    throw new IllegalArgumentException("fixed order violates causal edge "
                            + event.parentUid() + " -> " + uid + " on aggregate " + po.key());
                }
            }
            seen.add(uid);
        }
        if (!po.isLinearization(filtered)) {
            throw new IllegalArgumentException(
                    "fixed order is not a valid linearization of the partial order for " + po.key());
        }
        return filtered;
    }

    private static ReplayResult.MinimalSlice buildMinimalSlice(
            PartialOrder graph, AggregateRun run, RuleSet rules,
            ReplayResult.FailureSummary failure) {
        Set<String> ancestors = ancestors(graph, failure.eventUid());

        // The smallest reproducible prefix: every event this aggregate applied
        // up to and including the failed event, in the exact chosen order.
        // Events after the failure, or belonging to other aggregates, cannot
        // participate in reproducing this first violation and are excluded.
        List<String> prefixUids = new ArrayList<>();
        for (ReplayResult.Step step : run.trace().steps()) {
            prefixUids.add(step.eventUid());
            if (step.eventUid().equals(failure.eventUid())) {
                break;
            }
        }

        AggregateRun restricted = new AggregateRun(graph, rules);
        for (String uid : prefixUids) {
            EffectiveEvent event = graph.node(uid);
            if (event != null) {
                restricted.apply(event);
            }
        }
        List<ReplayResult.SliceStep> steps = new ArrayList<>();
        for (ReplayResult.Step step : restricted.trace().steps()) {
            steps.add(new ReplayResult.SliceStep(step.index(), step.eventUid(), step.type(),
                    step.fromState(), step.toState(), step.placeholder()));
        }
        boolean stillFails = restricted.firstFailure() != null;
        String explanation = stillFails
                ? "Per-aggregate replay prefix ending at the failed event, kept in the same "
                + "relative order, reproduces the first invariant violation. Events after the "
                + "failure or on other aggregates are excluded. The listed causal ancestors are "
                + "the events that must precede the failure; concurrent events around the slice "
                + "are reported separately and may be safely swapped unless flagged."
                : "Prefix slice did not reproduce; inspect the concurrent pair analysis for "
                + "ordering-sensitive events.";
        return new ReplayResult.MinimalSlice(failure.aggregateKey(), failure.eventUid(),
                new ArrayList<>(ancestors), steps, explanation);
    }

    private static Set<String> ancestors(PartialOrder graph, String uid) {
        Set<String> result = new HashSet<>();
        collectAncestors(graph, uid, result);
        return result;
    }

    private static void collectAncestors(PartialOrder graph, String uid, Set<String> sink) {
        EffectiveEvent event = graph.node(uid);
        if (event == null) {
            return;
        }
        if (event.parentUid() != null && !event.parentUid().isBlank()
                && graph.node(event.parentUid()) != null && sink.add(event.parentUid())) {
            collectAncestors(graph, event.parentUid(), sink);
        }
        EffectiveEvent previous = previousInService(graph, event);
        if (previous != null && sink.add(previous.uid())) {
            collectAncestors(graph, previous.uid(), sink);
        }
    }

    private static EffectiveEvent previousInService(PartialOrder graph, EffectiveEvent event) {
        EffectiveEvent previous = null;
        for (EffectiveEvent candidate : graph.nodes()) {
            if (candidate.service().equals(event.service()) && candidate.seq() < event.seq()) {
                if (previous == null || candidate.seq() > previous.seq()) {
                    previous = candidate;
                }
            }
        }
        return previous;
    }

    private static List<ReplayResult.ConcurrentPair> analyzeConcurrency(
            PartialOrder graph, AggregateRun baselineRun, RuleSet rules,
            ReplayResult.FailureSummary failure) {
        // Any event concurrent with the failure (or with one of its ancestors)
        // can legally be interleaved around it and is the swap surface. Events
        // that happen strictly after the failure cannot change the first failure.
        Set<String> candidate = new HashSet<>(ancestors(graph, failure.eventUid()));
        candidate.add(failure.eventUid());
        for (EffectiveEvent event : graph.nodes()) {
            for (String anchor : new ArrayList<>(candidate)) {
                if (graph.concurrent(event.uid(), anchor)) {
                    candidate.add(event.uid());
                }
            }
        }
        List<EffectiveEvent> relevant = new ArrayList<>();
        for (EffectiveEvent event : graph.nodes()) {
            if (candidate.contains(event.uid())) {
                relevant.add(event);
            }
        }
        List<ReplayResult.ConcurrentPair> report = new ArrayList<>();
        for (int i = 0; i < relevant.size(); i++) {
            for (int j = i + 1; j < relevant.size(); j++) {
                EffectiveEvent a = relevant.get(i);
                EffectiveEvent b = relevant.get(j);
                if (!graph.concurrent(a.uid(), b.uid())) {
                    continue;
                }
                boolean outcomeChanges = swapChangesOutcome(graph, rules, a.uid(), b.uid(),
                        baselineRun, failure);
                String note = outcomeChanges
                        ? "concurrent; swapping this pair changes which transition fires first "
                        + "and changes the first-failure conclusion"
                        : "concurrent; swapping this pair is legal and leaves the failure conclusion";
                report.add(new ReplayResult.ConcurrentPair(a.uid(), b.uid(), true,
                        outcomeChanges, note));
            }
        }
        return report;
    }

    private static boolean swapChangesOutcome(PartialOrder graph, RuleSet rules,
                                              String a, String b, AggregateRun baseline,
                                              ReplayResult.FailureSummary baselineFailure) {
        PartialOrder.Enumeration enumeration = graph.enumerateLinearizations(128);
        boolean sawFail = false;
        boolean sawPass = false;
        boolean sawDifferentFailure = false;
        for (List<String> order : enumeration.orders()) {
            if (!order.contains(a) || !order.contains(b)) {
                continue;
            }
            AggregateRun run = new AggregateRun(graph, rules);
            for (String uid : order) {
                EffectiveEvent event = graph.node(uid);
                if (event != null) {
                    run.apply(event);
                }
            }
            ReplayResult.FailureSummary failure = run.firstFailure();
            if (failure == null) {
                sawPass = true;
            } else {
                sawFail = true;
                if (!failure.eventUid().equals(baselineFailure.eventUid())) {
                    sawDifferentFailure = true;
                }
            }
        }
        return (sawFail && sawPass) || sawDifferentFailure;
    }

    private static ReplayResult.InterleavingReport enumerateOutcomes(
            Map<String, PartialOrder> graph, RuleSet rules, int bound,
            ReplayResult.FailureSummary baseline) {
        int effectiveBound = Math.max(1, Math.min(bound, 128));
        List<ReplayResult.OrderingOutcome> outcomes = new ArrayList<>();
        boolean truncated = false;

        for (PartialOrder po : graph.values()) {
            PartialOrder.Enumeration enumeration = po.enumerateLinearizations(effectiveBound);
            truncated |= enumeration.truncated();
            for (List<String> localOrder : enumeration.orders()) {
                AggregateRun run = new AggregateRun(po, rules);
                for (String uid : localOrder) {
                    EffectiveEvent event = po.node(uid);
                    if (event != null) {
                        run.apply(event);
                    }
                }
                ReplayResult.FailureSummary failure = run.firstFailure();
                outcomes.add(new ReplayResult.OrderingOutcome(localOrder, failure != null,
                        failure == null ? null : failure.eventUid(), po.key()));
            }
            if (outcomes.size() >= effectiveBound) {
                truncated = true;
                break;
            }
        }
        return new ReplayResult.InterleavingReport(effectiveBound,
                Math.min(outcomes.size(), effectiveBound), truncated, outcomes);
    }

    private static final class AggregateRun {
        private final PartialOrder graph;
        private final RuleSet rules;
        private final String key;
        private final String initialState;
        private String state;
        private final List<ReplayResult.Step> steps = new ArrayList<>();
        private ReplayResult.FailureSummary firstFailure;

        private AggregateRun(PartialOrder graph, RuleSet rules) {
            this.graph = graph;
            this.rules = rules;
            this.key = graph.key();
            RuleSet.AggregateRule rule = rules.match(key);
            this.initialState = rule != null && rule.initialState() != null
                    ? rule.initialState()
                    : rules.defaultInitialState();
            this.state = initialState;
        }

        private void apply(EffectiveEvent event) {
            int index = steps.size();
            if (event.placeholder()) {
                steps.add(new ReplayResult.Step(index, event.uid(), event.type(),
                        state, state, false, true,
                        "placeholder only; not a business event, state unchanged"));
                return;
            }
            RuleSet.AggregateRule rule = rules.match(key);
            if (rule == null) {
                steps.add(new ReplayResult.Step(index, event.uid(), event.type(),
                        state, state, false, false,
                        "no rule matches aggregate key; event left unvalidated"));
                return;
            }
            if (firstFailure != null) {
                steps.add(new ReplayResult.Step(index, event.uid(), event.type(),
                        state, state, false, false, "after first failure; not applied"));
                return;
            }
            if (!rule.allows(state, event.type())) {
                String message = "invariant violated: event " + event.type()
                        + " has no allowed transition from state " + state
                        + " for aggregate " + key;
                steps.add(new ReplayResult.Step(index, event.uid(), event.type(),
                        state, state, false, false, message));
                firstFailure = new ReplayResult.FailureSummary(key, event.uid(),
                        event.type(), state, message);
                return;
            }
            String target = rule.target(state, event.type());
            steps.add(new ReplayResult.Step(index, event.uid(), event.type(),
                    state, target, true, false, null));
            state = target;
        }

        private ReplayResult.AggregateTrace trace() {
            return new ReplayResult.AggregateTrace(key, initialState, state,
                    new ArrayList<>(steps));
        }

        private ReplayResult.FailureSummary firstFailure() {
            return firstFailure;
        }
    }
}
