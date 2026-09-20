package com.causaldbg.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The happens-before partial order for one aggregate (correlation key).
 *
 * <p>Ordering facts come from exactly two sources:
 * <ol>
 *   <li>explicit causal parent edges (event.parentUid)</li>
 *   <li>within-service monotonic sequence order</li>
 * </ol>
 * Wall-clock timestamps are display-only and never add an edge, so clock skew
 * cannot overwrite causal facts. Placeholders participate in ordering but are
 * flagged so replay can treat them as non-business events.
 */
public class PartialOrder {

    private final String key;
    private final List<EffectiveEvent> nodes;
    private final Map<String, EffectiveEvent> byUid = new LinkedHashMap<>();
    private final Map<String, Set<String>> successors = new HashMap<>();
    private final Map<String, Set<String>> predecessors = new HashMap<>();
    private final List<Edge> edges = new ArrayList<>();

    public record Edge(String from, String to, Reason reason) {
        public enum Reason {CAUSAL, SERVICE_ORDER}
    }

    public PartialOrder(String key, List<EffectiveEvent> events) {
        this.key = key;
        this.nodes = new ArrayList<>(events);
        this.nodes.sort(java.util.Comparator
                .comparing(EffectiveEvent::service)
                .thenComparingLong(EffectiveEvent::seq));
        for (EffectiveEvent event : this.nodes) {
            byUid.put(event.uid(), event);
            successors.put(event.uid(), new HashSet<>());
            predecessors.put(event.uid(), new HashSet<>());
        }
        buildCausalEdges();
        buildServiceOrderEdges();
    }

    private void addEdge(String from, String to, Edge.Reason reason) {
        if (from == null || to == null || !byUid.containsKey(from) || !byUid.containsKey(to)) {
            return;
        }
        if (from.equals(to)) {
            return;
        }
        if (successors.get(from).add(to)) {
            predecessors.get(to).add(from);
            edges.add(new Edge(from, to, reason));
        }
    }

    private void buildCausalEdges() {
        for (EffectiveEvent event : nodes) {
            if (event.parentUid() != null && !event.parentUid().isBlank()) {
                addEdge(event.parentUid(), event.uid(), Edge.Reason.CAUSAL);
            }
        }
    }

    private void buildServiceOrderEdges() {
        Map<String, List<EffectiveEvent>> byService = new HashMap<>();
        for (EffectiveEvent event : nodes) {
            byService.computeIfAbsent(event.service(), ignored -> new ArrayList<>()).add(event);
        }
        for (List<EffectiveEvent> serviceEvents : byService.values()) {
            serviceEvents.sort(java.util.Comparator
                    .comparingLong(EffectiveEvent::seq)
                    .thenComparing(EffectiveEvent::uid));
            for (int i = 1; i < serviceEvents.size(); i++) {
                addEdge(serviceEvents.get(i - 1).uid(), serviceEvents.get(i).uid(),
                        Edge.Reason.SERVICE_ORDER);
            }
        }
    }

    public String key() {
        return key;
    }

    public List<EffectiveEvent> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<Edge> edges() {
        return Collections.unmodifiableList(edges);
    }

    public EffectiveEvent node(String uid) {
        return byUid.get(uid);
    }

    public boolean reachable(String ancestor, String descendant) {
        if (ancestor.equals(descendant)) {
            return false;
        }
        Set<String> visited = new HashSet<>();
        List<String> stack = new ArrayList<>(successors.getOrDefault(ancestor, Set.of()));
        while (!stack.isEmpty()) {
            String current = stack.remove(stack.size() - 1);
            if (current.equals(descendant)) {
                return true;
            }
            if (visited.add(current)) {
                stack.addAll(successors.getOrDefault(current, Set.of()));
            }
        }
        return false;
    }

    /**
     * Two events are concurrent precisely when neither happens-before the
     * other. Such pairs may be swapped in a linearization.
     */
    public boolean concurrent(String a, String b) {
        return !reachable(a, b) && !reachable(b, a);
    }

    /**
     * Deterministic canonical linearization. The seed is rule/log/hypothesis
     * independent in structure but deterministic for this graph: repeatedly
     * pick the available node with the smallest uid, breaking ties by service
     * then sequence. It is exported so another tool can replay the exact order.
     */
    /** True if the given order respects every happens-before edge of this graph. */
    public boolean isLinearization(List<String> order) {
        if (order.size() != byUid.size()) {
            return false;
        }
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            if (!byUid.containsKey(order.get(i)) || position.put(order.get(i), i) != null) {
                return false;
            }
        }
        for (Edge edge : edges) {
            if (position.get(edge.from()) >= position.get(edge.to())) {
                return false;
            }
        }
        return true;
    }

    /** Detects directed cycles over causal + service-order edges via DFS coloring. */
    public boolean hasCycle() {
        Map<String, Integer> color = new HashMap<>();
        for (String uid : byUid.keySet()) {
            color.put(uid, 0);
        }
        for (String uid : byUid.keySet()) {
            if (color.get(uid) == 0 && dfsCycle(uid, color)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycle(String uid, Map<String, Integer> color) {
        color.put(uid, 1);
        for (String successor : successors.getOrDefault(uid, Set.of())) {
            int successorColor = color.getOrDefault(successor, 0);
            if (successorColor == 1) {
                return true;
            }
            if (successorColor == 0 && dfsCycle(successor, color)) {
                return true;
            }
        }
        color.put(uid, 2);
        return false;
    }

    public List<String> canonicalTopoSeed() {
        Map<String, Integer> indegree = new HashMap<>();
        for (String uid : byUid.keySet()) {
            indegree.put(uid, predecessors.get(uid).size());
        }
        List<String> order = new ArrayList<>();
        while (order.size() < byUid.size()) {
            EffectiveEvent chosen = null;
            for (EffectiveEvent candidate : nodes) {
                if (!order.contains(candidate.uid()) && indegree.get(candidate.uid()) == 0) {
                    if (chosen == null || less(candidate, chosen)) {
                        chosen = candidate;
                    }
                }
            }
            if (chosen == null) {
                throw new IllegalStateException("cycle in partial order for key " + key);
            }
            order.add(chosen.uid());
            for (String successor : successors.get(chosen.uid())) {
                indegree.merge(successor, -1, Integer::sum);
            }
        }
        return order;
    }

    private boolean less(EffectiveEvent a, EffectiveEvent b) {
        int byUidCmp = a.uid().compareTo(b.uid());
        if (byUidCmp != 0) {
            return byUidCmp < 0;
        }
        int byService = a.service().compareTo(b.service());
        if (byService != 0) {
            return byService < 0;
        }
        return a.seq() < b.seq();
    }

    /**
     * Enumerates bounded distinct linearizations using Kahn-level branching:
     * at each step, every currently-available node is a legal choice. Results
     * are capped at {@code limit}; {@code truncated=true} means the true number
     * is unknown.
     */
    public Enumeration enumerateLinearizations(int limit) {
        List<List<String>> found = new ArrayList<>();
        boolean[] truncated = {false};
        Map<String, Integer> indegree = new HashMap<>();
        for (String uid : byUid.keySet()) {
            indegree.put(uid, predecessors.get(uid).size());
        }
        enumerate(indegree, new ArrayList<>(), found, limit + 1, truncated);
        if (found.size() > limit) {
            return new Enumeration(found.subList(0, limit), true);
        }
        return new Enumeration(found, truncated[0]);
    }

    private void enumerate(Map<String, Integer> indegree, List<String> prefix,
                           List<List<String>> found, int hardCap, boolean[] truncated) {
        if (found.size() >= hardCap) {
            truncated[0] = true;
            return;
        }
        if (prefix.size() == byUid.size()) {
            found.add(new ArrayList<>(prefix));
            return;
        }
        List<EffectiveEvent> available = new ArrayList<>();
        for (EffectiveEvent candidate : nodes) {
            if (!prefix.contains(candidate.uid()) && indegree.get(candidate.uid()) == 0) {
                available.add(candidate);
            }
        }
        available.sort((a, b) -> a.uid().compareTo(b.uid()));
        for (EffectiveEvent choice : available) {
            Map<String, Integer> next = new HashMap<>(indegree);
            for (String successor : successors.get(choice.uid())) {
                next.merge(successor, -1, Integer::sum);
            }
            List<String> nextPrefix = new ArrayList<>(prefix);
            nextPrefix.add(choice.uid());
            enumerate(next, nextPrefix, found, hardCap, truncated);
            if (found.size() >= hardCap) {
                truncated[0] = true;
                return;
            }
        }
    }

    public record Enumeration(List<List<String>> orders, boolean truncated) {
    }
}
