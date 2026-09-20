package com.example.causalworkbench.service;

import com.example.causalworkbench.service.ReplayModels.AggregateState;
import com.example.causalworkbench.service.ReplayModels.Diagnosis;
import com.example.causalworkbench.service.ReplayModels.EventNode;
import com.example.causalworkbench.service.ReplayModels.Hypothesis;
import com.example.causalworkbench.service.ReplayModels.Linearization;
import com.example.causalworkbench.service.ReplayModels.PairAnalysis;
import com.example.causalworkbench.service.ReplayModels.ReplayRequest;
import com.example.causalworkbench.service.ReplayModels.ReplayResult;
import com.example.causalworkbench.service.ReplayModels.RuleDefinition;
import com.example.causalworkbench.service.ReplayModels.SliceNode;
import com.example.causalworkbench.service.ReplayModels.WorkbenchRules;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ReplayEngine {
    private static final String MISSING_PARENT = "MISSING_PARENT";
    private static final String CAUSAL_CYCLE = "CAUSAL_CYCLE";
    private static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";
    private static final String UNKNOWN_EVENT_TYPE = "UNKNOWN_EVENT_TYPE";

    public ReplayResult replay(ReplayRequest request) {
        return replay(request, null);
    }

    public ReplayResult replay(ReplayRequest request, List<String> forcedOrder) {
        WorkbenchRules rules = request.rules() == null ? new WorkbenchRules("INITIAL", Map.of()) : request.rules();
        Hypothesis hypothesis = request.hypothesis() == null
                ? new Hypothesis(true, true, Map.of(), List.of(), null)
                : request.hypothesis();

        Map<String, EventNode> byOriginalId = new LinkedHashMap<>();
        List<Diagnosis> structural = new ArrayList<>();
        for (EventNode event : request.events()) {
            if (event.placeholder()) {
                byOriginalId.put(event.placeholderForEventId(), event);
            } else if (!hypothesis.ignoreDuplicates()
                    || event.duplicateReceiptIds() == null || event.duplicateReceiptIds().isEmpty()) {
                byOriginalId.put(event.id(), event);
            }
        }

        Map<String, EventNode> effective = new LinkedHashMap<>();
        Map<String, Set<String>> children = new LinkedHashMap<>();
        for (EventNode event : byOriginalId.values()) {
            String effectiveId = event.placeholder() ? event.id() : event.id();
            String key = effectiveKey(event, hypothesis);
            List<String> warnings = new ArrayList<>(event.warnings() == null ? List.of() : event.warnings());
            if (!Objects.equals(key, event.originalKey())) {
                warnings.add("aggregate key adjusted from " + event.originalKey() + " to " + key);
            }
            effective.put(effectiveId, new EventNode(event.id(), event.serviceName(), event.seqNo(), event.originalKey(),
                    key, event.eventType(), event.payloadJson(), event.parentId(), event.wallClockMillis(),
                    event.receiptId(), event.contentHash(), event.placeholder(), event.placeholderForEventId(),
                    List.copyOf(event.duplicateReceiptIds() == null ? List.of() : event.duplicateReceiptIds()),
                    List.copyOf(warnings)));
        }

        for (EventNode event : List.copyOf(effective.values())) {
            if (event.placeholder()) {
                continue;
            }
            String parent = event.parentId();
            if (parent != null && !parent.isBlank()) {
                EventNode parentNode = byOriginalId.get(parent);
                if (parentNode == null) {
                    if (hypothesis.fillMissingParents()) {
                        EventNode placeholder = new EventNode("placeholder:" + parent, "placeholder", Long.MIN_VALUE,
                                parent, parent, "PLACEHOLDER", "{}", null, null, null, null, true, parent,
                                List.of(), List.of("synthetic missing-parent placeholder; never a business event"));
                        byOriginalId.put(parent, placeholder);
                        effective.put(placeholder.id(), placeholder);
                    } else {
                        structural.add(new Diagnosis(MISSING_PARENT, "ERROR", event.id(), event.effectiveKey(),
                                "event references causal parent " + parent + " which is absent from the log",
                                List.of(event.id())));
                    }
                }
            }
        }

        for (EventNode event : effective.values()) {
            if (event.parentId() != null && !event.parentId().isBlank()) {
                EventNode parentNode = byOriginalId.get(event.parentId());
                String parentEffectiveId = parentNode == null ? null
                        : parentNode.placeholder() ? parentNode.id() : parentNode.id();
                if (parentEffectiveId != null) {
                    children.computeIfAbsent(parentEffectiveId, ignored -> new LinkedHashSet<>()).add(event.id());
                }
            }
        }

        addServiceOrderEdges(effective, children);
        List<String> cycle = findCycle(effective, children);
        if (!cycle.isEmpty()) {
            List<String> order = cycle;
            String cycleText = String.join(" -> ", cycle) + " -> " + cycle.get(0);
            structural.add(new Diagnosis(CAUSAL_CYCLE, "ERROR", cycle.get(cycle.size() - 1), null,
                    "causal graph contains cycle: " + cycleText, List.copyOf(cycle)));
            return baseResult("FAILED", order, structural, effective, rules, hypothesis,
                    new Linearization(order, "FAILED", cycle.get(cycle.size() - 1), cycle.size(),
                            CAUSAL_CYCLE, cycleText));
        }

        if (forcedOrder != null) {
            List<String> invalid = invalidTopologicalOrder(forcedOrder, effective, children);
            if (!invalid.isEmpty()) {
                String message = "fixed order violates causal edge " + invalid.get(0) + " -> " + invalid.get(1);
                structural.add(new Diagnosis("INVALID_TOPOLOGICAL_ORDER", "ERROR", invalid.get(1), null,
                        message, List.copyOf(invalid)));
                return baseResult("FAILED", List.copyOf(forcedOrder), structural, effective, rules, hypothesis,
                        new Linearization(List.copyOf(forcedOrder), "FAILED", invalid.get(1), null,
                                "INVALID_TOPOLOGICAL_ORDER", message));
            }
        }

        addDuplicateAndGapDiagnoses(effective, structural);
        List<String> deterministicOrder = forcedOrder != null ? List.copyOf(forcedOrder)
                : deterministicTopologicalOrder(effective, children, hypothesis.seedOrder());
        if (deterministicOrder.size() != effective.size()) {
            structural.add(new Diagnosis(CAUSAL_CYCLE, "ERROR", null, null,
                    "topological ordering could not include every event", List.copyOf(effective.keySet())));
        }

        StepResult deterministicStep = runOrder(deterministicOrder, effective, rules);
        List<Diagnosis> all = new ArrayList<>(structural);
        all.addAll(deterministicStep.diagnoses());
        Diagnosis earliest = structural.isEmpty() ? deterministicStep.failure() : structural.get(0);

        List<Linearization> linearizations = new ArrayList<>();
        linearizations.add(linearization(deterministicOrder, deterministicStep, "deterministic"));
        boolean boundReached = false;
        int explored = 1;
        Integer bound = hypothesis.interleavingBound();
        int actualBound = bound == null || bound < 0 ? 100 : Math.min(bound, 1000);
        if (actualBound > 1) {
            Enumeration enumeration = enumerate(effective, children, rules, deterministicOrder, actualBound,
                    earliest == null ? null : earliest.eventId());
            linearizations.addAll(enumeration.linearizations);
            explored += enumeration.linearizations.size();
            boundReached = enumeration.boundReached;
            Diagnosis earlier = enumeration.earliest;
            if (earlier != null && (earliest == null || rank(earlier, linearizations) < rank(earliest, linearizations))) {
                earliest = earlier;
            }
        }

        List<SliceNode> minimalSlice = earliest == null
                ? List.of()
                : minimizeSlice(earliest, deterministicOrder, effective, children, rules, structural);
        List<PairAnalysis> pairs = analyzePairs(effective, children, rules);
        String seed = String.join(",", deterministicOrder);

        List<AggregateState> stateList = new ArrayList<>(deterministicStep.states().values());
        return new ReplayResult(earliest == null ? "PASSED" : "FAILED",
                earliest == null ? null : earliest.code(),
                earliest == null ? null : earliest.eventId(),
                deterministicStep.failureRank() == 0 ? null : deterministicStep.failureRank(),
                earliest == null ? null : earliest.message(),
                List.copyOf(deterministicOrder), seed, List.copyOf(all), List.copyOf(minimalSlice),
                List.copyOf(stateList),
                List.copyOf(pairs), List.copyOf(linearizations), boundReached, explored,
                bound);
    }

    private String effectiveKey(EventNode event, Hypothesis hypothesis) {
        if (event.placeholder()) {
            return event.originalKey();
        }
        String adjusted = hypothesis.keyAdjustments() == null ? null
                : hypothesis.keyAdjustments().get(event.id());
        return adjusted == null || adjusted.isBlank() ? event.originalKey() : adjusted;
    }

    private void addServiceOrderEdges(Map<String, EventNode> events, Map<String, Set<String>> children) {
        Map<String, List<EventNode>> byService = new HashMap<>();
        for (EventNode event : events.values()) {
            if (event.placeholder()) {
                continue;
            }
            byService.computeIfAbsent(event.serviceName(), ignored -> new ArrayList<>()).add(event);
        }
        for (List<EventNode> serviceEvents : byService.values()) {
            serviceEvents.sort(Comparator.comparingLong(EventNode::seqNo).thenComparing(EventNode::id));
            for (int index = 1; index < serviceEvents.size(); index++) {
                EventNode previous = serviceEvents.get(index - 1);
                EventNode current = serviceEvents.get(index);
                children.computeIfAbsent(previous.id(), ignored -> new LinkedHashSet<>()).add(current.id());
            }
        }
    }

    private List<String> findCycle(Map<String, EventNode> events, Map<String, Set<String>> children) {
        Set<String> visited = new HashSet<>();
        Set<String> stack = new LinkedHashSet<>();
        for (String node : topologicalNodeOrder(events)) {
            List<String> cycle = findCycleDfs(node, children, visited, stack, new LinkedHashSet<>());
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    private List<String> findCycleDfs(String node, Map<String, Set<String>> children, Set<String> visited,
                                      Set<String> stack, Set<String> safe) {
        if (safe.contains(node)) {
            return List.of();
        }
        if (stack.contains(node)) {
            List<String> path = new ArrayList<>(stack);
            return path.subList(path.indexOf(node), path.size());
        }
        if (visited.contains(node)) {
            return List.of();
        }
        visited.add(node);
        stack.add(node);
        for (String child : sorted(children.getOrDefault(node, Set.of()))) {
            List<String> cycle = findCycleDfs(child, children, visited, stack, safe);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        stack.remove(node);
        safe.add(node);
        return List.of();
    }

    private void addDuplicateAndGapDiagnoses(Map<String, EventNode> events, List<Diagnosis> diagnoses) {
        Map<String, List<EventNode>> byService = new HashMap<>();
        for (EventNode event : events.values()) {
            if (!event.placeholder()) {
                byService.computeIfAbsent(event.serviceName(), ignored -> new ArrayList<>()).add(event);
            }
        }
        for (List<EventNode> serviceEvents : byService.values()) {
            serviceEvents.sort(Comparator.comparingLong(EventNode::seqNo).thenComparing(EventNode::id));
            for (int index = 1; index < serviceEvents.size(); index++) {
                EventNode previous = serviceEvents.get(index - 1);
                EventNode current = serviceEvents.get(index);
                if (current.seqNo() == previous.seqNo()
                        && !Objects.equals(current.contentHash(), previous.contentHash())) {
                    diagnoses.add(new Diagnosis("SEQ_CONFLICT", "WARNING", current.id(), current.effectiveKey(),
                            "same service sequence " + current.serviceName() + "#" + current.seqNo()
                                    + " has different content; neither receipt rewrites the other",
                            List.of(previous.id(), current.id())));
                }
                if (current.seqNo() > previous.seqNo() + 1) {
                    diagnoses.add(new Diagnosis("SEQ_GAP", "INFO", current.id(), current.effectiveKey(),
                            "service sequence has a gap after " + previous.seqNo() + "; late events remain valid",
                            List.of(previous.id(), current.id())));
                }
            }
            for (EventNode event : serviceEvents) {
                if (event.duplicateReceiptIds() != null && !event.duplicateReceiptIds().isEmpty()) {
                    diagnoses.add(new Diagnosis("DUPLICATE_DELIVERY", "INFO", event.id(), event.effectiveKey(),
                            "ignored " + event.duplicateReceiptIds().size()
                                    + " byte-identical duplicate deliveries",
                            List.of(event.id())));
                }
            }
        }
    }

    private List<String> topologicalNodeOrder(Map<String, EventNode> events) {
        return events.values().stream()
                .sorted(Comparator.comparing(EventNode::serviceName)
                        .thenComparingLong(EventNode::seqNo)
                        .thenComparing(EventNode::id))
                .map(EventNode::id)
                .toList();
    }

    private List<String> deterministicTopologicalOrder(Map<String, EventNode> events,
                                                       Map<String, Set<String>> children,
                                                       List<String> seedOrder) {
        Map<String, Integer> seededRank = new HashMap<>();
        if (seedOrder != null) {
            for (int index = 0; index < seedOrder.size(); index++) {
                seededRank.put(seedOrder.get(index), index);
            }
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, Set<String>> parents = new LinkedHashMap<>();
        for (String node : events.keySet()) {
            indegree.put(node, 0);
            parents.put(node, new LinkedHashSet<>());
        }
        for (Map.Entry<String, Set<String>> edge : children.entrySet()) {
            for (String child : edge.getValue()) {
                if (events.containsKey(edge.getKey()) && events.containsKey(child)) {
                    indegree.merge(child, 1, Integer::sum);
                    parents.computeIfAbsent(child, ignored -> new LinkedHashSet<>()).add(edge.getKey());
                }
            }
        }
        List<String> available = new ArrayList<>();
        for (String node : events.keySet()) {
            if (indegree.get(node) == 0) {
                available.add(node);
            }
        }
        Comparator<String> comparator = (left, right) -> {
            Integer leftRank = seededRank.get(left);
            Integer rightRank = seededRank.get(right);
            if (leftRank != null || rightRank != null) {
                if (leftRank == null) {
                    return 1;
                }
                if (rightRank == null) {
                    return -1;
                }
                return Integer.compare(leftRank, rightRank);
            }
            EventNode leftEvent = events.get(left);
            EventNode rightEvent = events.get(right);
            int service = leftEvent.serviceName().compareTo(rightEvent.serviceName());
            if (service != 0) {
                return service;
            }
            int seq = Long.compare(leftEvent.seqNo(), rightEvent.seqNo());
            if (seq != 0) {
                return seq;
            }
            return left.compareTo(right);
        };
        List<String> result = new ArrayList<>();
        while (!available.isEmpty()) {
            available.sort(comparator);
            String node = available.remove(0);
            result.add(node);
            for (String child : sorted(children.getOrDefault(node, Set.of()))) {
                if (!events.containsKey(child)) {
                    continue;
                }
                int next = indegree.merge(child, -1, Integer::sum);
                if (next == 0) {
                    available.add(child);
                }
            }
        }
        return result;
    }

    private List<String> invalidTopologicalOrder(List<String> order, Map<String, EventNode> events,
                                                 Map<String, Set<String>> children) {
        if (order.size() != events.size() || !events.keySet().containsAll(order)
                || new HashSet<>(order).size() != order.size()) {
            return List.of("graph", "order must contain every event exactly once");
        }
        Map<String, Integer> rank = new HashMap<>();
        for (int index = 0; index < order.size(); index++) {
            rank.put(order.get(index), index);
        }
        for (Map.Entry<String, Set<String>> edge : children.entrySet()) {
            for (String child : edge.getValue()) {
                Integer parentRank = rank.get(edge.getKey());
                Integer childRank = rank.get(child);
                if (parentRank == null || childRank == null || parentRank >= childRank) {
                    return List.of(edge.getKey(), child);
                }
            }
        }
        return List.of();
    }

    private StepResult runOrder(List<String> order, Map<String, EventNode> events, WorkbenchRules rules) {
        Map<String, MutableState> states = new LinkedHashMap<>();
        Map<String, List<String>> eventsByAggregate = new LinkedHashMap<>();
        List<Diagnosis> diagnoses = new ArrayList<>();
        Diagnosis failure = null;
        int failureRank = 0;
        int rank = 0;
        for (String id : order) {
            rank++;
            EventNode event = events.get(id);
            if (event == null) {
                continue;
            }
            for (String warning : event.warnings()) {
                diagnoses.add(new Diagnosis("HYPOTHESIS_NOTE", "INFO", event.id(), event.effectiveKey(),
                        warning, List.of(event.id())));
            }
            if (event.placeholder()) {
                continue;
            }
            MutableState state = states.computeIfAbsent(event.effectiveKey(),
                    ignored -> new MutableState(rules.initialState() == null ? "INITIAL" : rules.initialState()));
            eventsByAggregate.computeIfAbsent(event.effectiveKey(), ignored -> new ArrayList<>()).add(event.id());
            RuleDefinition definition = rules.eventTypes() == null ? null
                    : rules.eventTypes().get(event.eventType());
            if (definition == null) {
                Diagnosis diagnosis = new Diagnosis(UNKNOWN_EVENT_TYPE, "ERROR", event.id(), event.effectiveKey(),
                        "event type " + event.eventType() + " has no transition in the bound rule set"
                                + " (topological rank " + rank + ")",
                        List.of(event.id()));
                diagnoses.add(diagnosis);
                if (failure == null) {
                    failure = diagnosis;
                    failureRank = rank;
                }
                break;
            }
            String initialState = state.state;
            if (!Objects.equals(initialState, state.state)) {
                String message = event.eventType() + " requires state " + initialState + " but aggregate "
                        + event.effectiveKey() + " is " + state.state;
                Diagnosis diagnosis = new Diagnosis(ILLEGAL_TRANSITION, "ERROR", event.id(),
                        event.effectiveKey(), message + " (topological rank " + rank + ")",
                        List.of(event.id()));
                diagnoses.add(diagnosis);
                if (failure == null) {
                    failure = diagnosis;
                    failureRank = rank;
                }
                break;
            }
            String target = definition.transitions().get(state.state);
            if (target == null) {
                String message = event.eventType() + " has no legal transition from " + state.state
                        + " on aggregate " + event.effectiveKey();
                Diagnosis diagnosis = new Diagnosis(ILLEGAL_TRANSITION, "ERROR", event.id(),
                        event.effectiveKey(), message + " (topological rank " + rank + ")",
                        List.of(event.id()));
                diagnoses.add(diagnosis);
                if (failure == null) {
                    failure = diagnosis;
                    failureRank = rank;
                }
                break;
            }
            state.state = target;
            state.lastEventId = event.id();
        }
        Map<String, AggregateState> result = new LinkedHashMap<>();
        for (Map.Entry<String, MutableState> entry : states.entrySet()) {
            result.put(entry.getKey(), new AggregateState(entry.getKey(), entry.getValue().state,
                    entry.getValue().lastEventId, List.copyOf(
                            eventsByAggregate.getOrDefault(entry.getKey(), List.of()))));
        }
        return new StepResult(result, diagnoses, failure, failureRank);
    }

    private Enumeration enumerate(Map<String, EventNode> events, Map<String, Set<String>> children,
                                  WorkbenchRules rules, List<String> deterministic, int bound,
                                  String deterministicFailureId) {
        List<Linearization> linearizations = new ArrayList<>();
        Diagnosis earliest = null;
        Map<String, Integer> indegree = indegrees(events, children);
        enumerateDfs(events, children, rules, new ArrayList<>(), new HashMap<>(indegree), bound,
                linearizations, deterministic, deterministicFailureId == null ? new HashSet<>() : new HashSet<>());
        for (Linearization linearization : linearizations) {
            if (!"FAILED".equals(linearization.outcome())) {
                continue;
            }
            if (earliest == null || linearization.failedRank() < rankOf(earliest.eventId(), linearizations)) {
                earliest = new Diagnosis(linearization.failureCode(), "ERROR", linearization.failedEventId(),
                        null, linearization.failureMessage(), List.of(linearization.failedEventId()));
            }
        }
        return new Enumeration(linearizations, linearizations.size() >= bound - 1, earliest);
    }

    private boolean enumerateDfs(Map<String, EventNode> events, Map<String, Set<String>> children,
                                 WorkbenchRules rules, List<String> prefix, Map<String, Integer> indegree,
                                 int bound, List<Linearization> output, List<String> deterministic,
                                 Set<String> skippedLinearizations) {
        if (prefix.size() == events.size()) {
            if (!prefix.equals(deterministic) && output.size() < bound - 1) {
                StepResult step = runOrder(prefix, events, rules);
                output.add(linearization(prefix, step, "bounded-interleaving"));
            }
            return output.size() < bound;
        }
        List<String> available = new ArrayList<>();
        for (String node : events.keySet()) {
            if (!prefix.contains(node) && indegree.getOrDefault(node, 0) == 0) {
                available.add(node);
            }
        }
        available.sort(Comparator.comparingInt((String node) -> deterministic.indexOf(node))
                .thenComparing(node -> node));
        for (String node : available) {
            prefix.add(node);
            for (String child : children.getOrDefault(node, Set.of())) {
                indegree.merge(child, -1, Integer::sum);
            }
            boolean keepGoing = enumerateDfs(events, children, rules, prefix, indegree, bound, output,
                    deterministic, skippedLinearizations);
            prefix.remove(prefix.size() - 1);
            for (String child : children.getOrDefault(node, Set.of())) {
                indegree.merge(child, 1, Integer::sum);
            }
            if (!keepGoing) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Integer> indegrees(Map<String, EventNode> events, Map<String, Set<String>> children) {
        Map<String, Integer> indegree = new HashMap<>();
        for (String node : events.keySet()) {
            indegree.put(node, 0);
        }
        for (Map.Entry<String, Set<String>> edge : children.entrySet()) {
            for (String child : edge.getValue()) {
                if (events.containsKey(edge.getKey()) && events.containsKey(child)) {
                    indegree.merge(child, 1, Integer::sum);
                }
            }
        }
        return indegree;
    }

    private Linearization linearization(List<String> order, StepResult step, String kind) {
        if (step.failure() == null) {
            return new Linearization(List.copyOf(order), "PASSED", null, null, null, kind);
        }
        return new Linearization(List.copyOf(order), "FAILED", step.failure().eventId(), step.failureRank(),
                step.failure().code(), step.failure().message());
    }

    private int rank(Diagnosis diagnosis, List<Linearization> linearizations) {
        return rankOf(diagnosis.eventId(), linearizations);
    }

    private int rankOf(String eventId, List<Linearization> linearizations) {
        for (Linearization linearization : linearizations) {
            if (eventId.equals(linearization.failedEventId())) {
                return linearization.failedRank() == null ? Integer.MAX_VALUE : linearization.failedRank();
            }
        }
        return Integer.MAX_VALUE;
    }

    private List<SliceNode> minimizeSlice(Diagnosis failure, List<String> deterministic,
                                          Map<String, EventNode> events, Map<String, Set<String>> children,
                                          WorkbenchRules rules, List<Diagnosis> structural) {
        Set<String> required = new LinkedHashSet<>();
        if (failure.eventId() != null) {
            required.add(failure.eventId());
            addAncestors(failure.eventId(), events, children, required);
        }
        for (Diagnosis diagnosis : structural) {
            required.addAll(diagnosis.evidenceIds());
        }
        if (failure.eventId() != null) {
            String aggregateKey = events.get(failure.eventId()).effectiveKey();
            for (String id : deterministic) {
                EventNode event = events.get(id);
                if (Objects.equals(event.effectiveKey(), aggregateKey)) {
                    required.add(id);
                }
                if (id.equals(failure.eventId())) {
                    break;
                }
            }
        }
        List<String> candidateOrder = deterministic.stream().filter(required::contains).toList();
        for (String candidate : new ArrayList<>(required)) {
            EventNode event = events.get(candidate);
            if (event == null || event.placeholder() || candidate.equals(failure.eventId())) {
                continue;
            }
            String failureKey = failure.aggregateKey() == null && events.containsKey(failure.eventId())
                    ? events.get(failure.eventId()).effectiveKey() : failure.aggregateKey();
            boolean necessary = Objects.equals(event.effectiveKey(), failureKey)
                    || isCausalAncestor(candidate, failure.eventId(), children);
            if (!necessary) {
                List<String> trial = new ArrayList<>(required);
                trial.remove(candidate);
                List<String> trialOrder = deterministic.stream().filter(trial::contains).toList();
                StepResult result = runOrder(trialOrder, events, rules);
                if (result.failure() != null && Objects.equals(result.failure().eventId(), failure.eventId())
                    && Objects.equals(result.failure().code(), failure.code())) {
                    required.remove(candidate);
                    candidateOrder = trialOrder;
                }
            }
        }
        Set<String> minimal = new LinkedHashSet<>(candidateOrder);
        List<SliceNode> nodes = new ArrayList<>();
        for (String id : candidateOrder) {
            EventNode event = events.get(id);
            String reason;
            if (id.equals(failure.eventId())) {
                reason = "failed event";
            } else if (event != null && event.placeholder()) {
                reason = "synthetic placeholder for absent causal parent";
            } else if (isCausalAncestor(id, failure.eventId(), children)) {
                reason = "causal ancestor needed to reproduce the transition";
            } else {
                reason = "service-order predecessor needed to reproduce aggregate state";
            }
            nodes.add(new SliceNode(id, reason));
        }
        return nodes;
    }

    private void addAncestors(String id, Map<String, EventNode> events, Map<String, Set<String>> children,
                              Set<String> ancestors) {
        Map<String, Set<String>> parents = parents(children);
        ArrayDeque<String> queue = new ArrayDeque<>(parents.getOrDefault(id, Set.of()));
        while (!queue.isEmpty()) {
            String parent = queue.remove();
            if (ancestors.add(parent)) {
                queue.addAll(parents.getOrDefault(parent, Set.of()));
            }
        }
    }

    private boolean isCausalAncestor(String candidate, String target, Map<String, Set<String>> children) {
        ArrayDeque<String> queue = new ArrayDeque<>(children.getOrDefault(candidate, Set.of()));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String node = queue.remove();
            if (node.equals(target)) {
                return true;
            }
            if (seen.add(node)) {
                queue.addAll(children.getOrDefault(node, Set.of()));
            }
        }
        return false;
    }

    private Map<String, Set<String>> parents(Map<String, Set<String>> children) {
        Map<String, Set<String>> parents = new LinkedHashMap<>();
        children.forEach((parent, kids) -> kids.forEach(child ->
                parents.computeIfAbsent(child, ignored -> new LinkedHashSet<>()).add(parent)));
        return parents;
    }

    private List<PairAnalysis> analyzePairs(Map<String, EventNode> events, Map<String, Set<String>> children,
                                            WorkbenchRules rules) {
        Map<String, Set<String>> ancestorClosure = ancestorClosure(events, children);
        List<EventNode> real = events.values().stream().filter(event -> !event.placeholder()).toList();
        List<PairAnalysis> result = new ArrayList<>();
        int checked = 0;
        for (int leftIndex = 0; leftIndex < real.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < real.size(); rightIndex++) {
                if (checked++ >= 80) {
                    return result;
                }
                EventNode left = real.get(leftIndex);
                EventNode right = real.get(rightIndex);
                boolean ordered = ancestorClosure.getOrDefault(left.id(), Set.of()).contains(right.id())
                        || ancestorClosure.getOrDefault(right.id(), Set.of()).contains(left.id());
                if (ordered) {
                    continue;
                }
                String effect;
                boolean commutes;
                if (!Objects.equals(left.effectiveKey(), right.effectiveKey())) {
                    commutes = true;
                    effect = "different aggregates; swapping cannot change either aggregate state";
                } else {
                    Boolean swapOutcome = compareAdjacentSwap(left.id(), right.id(), events, children, rules);
                    commutes = Boolean.TRUE.equals(swapOutcome);
                    effect = swapOutcome == null
                            ? "same aggregate and non-adjacent in every explored order; swap may change result"
                            : swapOutcome ? "same aggregate but swapping preserves replay outcome"
                            : "same aggregate; swapping changes the failure or final state";
                }
                result.add(new PairAnalysis(left.id(), right.id(), true, commutes, effect));
            }
        }
        return result;
    }

    private Boolean compareAdjacentSwap(String leftId, String rightId, Map<String, EventNode> events,
                                        Map<String, Set<String>> children, WorkbenchRules rules) {
        List<String> baseOrder = deterministicTopologicalOrder(events, children, List.of());
        int leftIndex = baseOrder.indexOf(leftId);
        int rightIndex = baseOrder.indexOf(rightId);
        if (Math.abs(leftIndex - rightIndex) != 1) {
            return null;
        }
        StepResult base = runOrder(baseOrder, events, rules);
        List<String> swapped = new ArrayList<>(baseOrder);
        Collections.swap(swapped, leftIndex, rightIndex);
        StepResult after = runOrder(swapped, events, rules);
        return Objects.equals(base.failure() == null ? null : base.failure().code(),
                after.failure() == null ? null : after.failure().code())
                && Objects.equals(base.failure() == null ? null : base.failure().eventId(),
                after.failure() == null ? null : after.failure().eventId())
                && Objects.equals(statesSignature(base), statesSignature(after));
    }

    private String statesSignature(StepResult step) {
        return step.states().values().stream()
                .map(state -> state.aggregateKey() + "=" + state.state() + "@" + state.lastEventId())
                .sorted()
                .reduce("", (left, right) -> left + "|" + right);
    }

    private Map<String, Set<String>> ancestorClosure(Map<String, EventNode> events,
                                                     Map<String, Set<String>> children) {
        Map<String, Set<String>> closure = new LinkedHashMap<>();
        for (String node : events.keySet()) {
            Set<String> reachable = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(children.getOrDefault(node, Set.of()));
            while (!queue.isEmpty()) {
                String current = queue.remove();
                if (reachable.add(current)) {
                    queue.addAll(children.getOrDefault(current, Set.of()));
                }
            }
            closure.put(node, reachable);
        }
        return closure;
    }

    private ReplayResult baseResult(String outcome, List<String> order, List<Diagnosis> structural,
                                    Map<String, EventNode> events, WorkbenchRules rules,
                                    Hypothesis hypothesis, Linearization linearization) {
        return new ReplayResult(outcome,
                structural.isEmpty() ? null : structural.get(0).code(),
                structural.isEmpty() ? null : structural.get(0).eventId(),
                null,
                structural.isEmpty() ? null : structural.get(0).message(),
                List.copyOf(order), String.join(",", order), List.copyOf(structural),
                structural.stream().map(diagnosis -> new SliceNode(diagnosis.eventId() == null
                        ? "graph" : diagnosis.eventId(), "structural failure evidence")).toList(),
                List.of(), List.of(), List.of(linearization), false, 1, hypothesis.interleavingBound());
    }

    private List<String> sorted(Set<String> values) {
        List<String> list = new ArrayList<>(values);
        Collections.sort(list);
        return list;
    }

    private static final class MutableState {
        private String state;
        private String lastEventId;

        private MutableState(String state) {
            this.state = state;
        }
    }

    private record StepResult(Map<String, AggregateState> states, List<Diagnosis> diagnoses,
                              Diagnosis failure, int failureRank) {
    }

    private record Enumeration(List<Linearization> linearizations, boolean boundReached, Diagnosis earliest) {
    }
}
