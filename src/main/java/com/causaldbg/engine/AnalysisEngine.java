package com.causaldbg.engine;

import com.causaldbg.domain.Hypothesis;
import com.causaldbg.domain.RawEvent;
import com.causaldbg.domain.RuleSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless facade: raw evidence + pinned rule version + replay hypothesis
 * in, effective set / partial orders / replay diagnosis out. Derived results
 * are never written back over the evidence.
 */
public final class AnalysisEngine {

    private AnalysisEngine() {
    }

    public record Analysis(
            EventSetBuilder.Result eventSet,
            Map<String, PartialOrder> graph,
            ReplayResult replay
    ) {
    }

    public static Analysis analyze(List<RawEvent> raw, RuleSet rules, Hypothesis hypothesis,
                                   List<String> fixedOrder, int interleavingBound) {
        EventSetBuilder.Result eventSet = EventSetBuilder.build(raw, hypothesis);
        Map<String, List<EffectiveEvent>> byKey = new LinkedHashMap<>();
        for (EffectiveEvent event : eventSet.events()) {
            byKey.computeIfAbsent(event.key(), ignored -> new ArrayList<>()).add(event);
        }
        Map<String, PartialOrder> graph = new LinkedHashMap<>();
        for (Map.Entry<String, List<EffectiveEvent>> entry : byKey.entrySet()) {
            graph.put(entry.getKey(), new PartialOrder(entry.getKey(), entry.getValue()));
        }
        ReplayResult replay = Replayer.replay(graph, rules, fixedOrder, interleavingBound,
                eventSet.settledPlaceholderParents());
        return new Analysis(eventSet, graph, replay);
    }
}
