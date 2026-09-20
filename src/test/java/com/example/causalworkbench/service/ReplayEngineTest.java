package com.example.causalworkbench.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.causalworkbench.service.ReplayModels.EventNode;
import com.example.causalworkbench.service.ReplayModels.Hypothesis;
import com.example.causalworkbench.service.ReplayModels.ReplayRequest;
import com.example.causalworkbench.service.ReplayModels.ReplayResult;
import com.example.causalworkbench.service.ReplayModels.RuleDefinition;
import com.example.causalworkbench.service.ReplayModels.WorkbenchRules;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplayEngineTest {
    private final ReplayEngine engine = new ReplayEngine();

    @Test
    void validatesStateTransitionsWithoutUsingWallClock() {
        ReplayResult result = engine.replay(new ReplayRequest(rules(), List.of(
                event("pay-1", "payments", 1, "a", "COMPLETE", null, 900L),
                event("create-1", "orders", 1, "a", "CREATE", null, 1000L)),
                hypothesis(10)));

        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.earliestFailureCode()).isEqualTo("ILLEGAL_TRANSITION");
        assertThat(result.deterministicOrder()).containsExactly("create-1", "pay-1");
        assertThat(result.minimalSlice()).extracting(slice -> slice.eventId())
                .containsExactly("create-1", "pay-1");
    }

    @Test
    void explicitCausalEdgeOverridesServiceAndTimestampOrder() {
        ReplayResult result = engine.replay(new ReplayRequest(rules(), List.of(
                event("complete-1", "b", 2, "a", "COMPLETE", "create-1", 10L),
                event("create-1", "a", 1, "a", "CREATE", null, 1000L)),
                hypothesis(10)));

        assertThat(result.deterministicOrder()).containsExactly("create-1", "complete-1");
        assertThat(result.outcome()).isEqualTo("PASSED");
    }

    @Test
    void missingParentIsStructuralFailureWithoutPlaceholderHypothesis() {
        ReplayResult result = engine.replay(new ReplayRequest(rules(), List.of(
                event("child", "a", 1, "a", "CREATE", "missing", null)),
                new Hypothesis(true, false, Map.of(), List.of(), 1)));

        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.earliestFailureCode()).isEqualTo("MISSING_PARENT");
        assertThat(result.minimalSlice()).extracting(slice -> slice.eventId()).containsExactly("child");
    }

    @Test
    void placeholderIsMarkedAndNeverRunsBusinessTransition() {
        ReplayResult result = engine.replay(new ReplayRequest(rules(), List.of(
                event("child", "a", 1, "a", "CREATE", "missing", null)),
                hypothesis(1)));

        assertThat(result.outcome()).isEqualTo("PASSED");
        assertThat(result.deterministicOrder()).containsExactly("placeholder:missing", "child");
        assertThat(result.diagnoses()).noneMatch(diagnosis -> "UNKNOWN_EVENT_TYPE".equals(diagnosis.code()));
    }

    @Test
    void boundedInterleavingMarksUnknownBeyondTheBoundary() {
        ReplayResult result = engine.replay(new ReplayRequest(rules(), List.of(
                event("a1", "a", 1, "a", "CREATE", null, 1L),
                event("b1", "b", 1, "b", "CREATE", null, 1L),
                event("c1", "c", 1, "c", "CREATE", null, 1L)),
                new Hypothesis(true, false, Map.of(), List.of(), 2)));

        assertThat(result.boundReached()).isTrue();
        assertThat(result.linearizations()).hasSize(2);
    }

    @Test
    void concurrentEventsOnDifferentAggregatesCommute() {
        ReplayResult result = engine.replay(new ReplayRequest(rules(), List.of(
                event("a1", "a", 1, "a", "CREATE", null, 1L),
                event("b1", "b", 1, "b", "CREATE", null, 1L)),
                hypothesis(10)));

        assertThat(result.concurrentPairs()).hasSize(1);
        assertThat(result.concurrentPairs().get(0).commutes()).isTrue();
    }

    @Test
    void fixedOrderThatViolatesCausalEdgeIsRejected() {
        ReplayResult result = engine.replay(new ReplayRequest(rules(), List.of(
                event("child", "b", 1, "a", "COMPLETE", "parent", null),
                event("parent", "a", 1, "a", "CREATE", null, null)),
                hypothesis(1)), List.of("child", "parent"));

        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.earliestFailureCode()).isEqualTo("INVALID_TOPOLOGICAL_ORDER");
    }

    private EventNode event(String id, String service, long seq, String key, String type, String parent,
                            Long timestamp) {
        return new EventNode(id, service, seq, key, key, type, "{}", parent, timestamp,
                "receipt-" + id, "hash-" + id, false, null, List.of(), List.of());
    }

    private Hypothesis hypothesis(Integer bound) {
        return new Hypothesis(true, true, Map.of(), List.of(), bound);
    }

    private WorkbenchRules rules() {
        return new WorkbenchRules("CREATED", Map.of(
                "CREATE", new RuleDefinition(null, Map.of("CREATED", "ACTIVE")),
                "COMPLETE", new RuleDefinition(null, Map.of("ACTIVE", "COMPLETED"))));
    }
}
