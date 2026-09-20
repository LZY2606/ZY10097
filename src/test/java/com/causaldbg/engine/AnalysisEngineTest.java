package com.causaldbg.engine;

import com.causaldbg.domain.DefaultRules;
import com.causaldbg.domain.Hypothesis;
import com.causaldbg.domain.RawEvent;
import com.causaldbg.domain.RuleSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisEngineTest {

    private RawEvent event(String uid, String service, long seq, String key,
                           String parent, String type) {
        return new RawEvent(0, 0, uid, service, seq, key, parent, type,
                "h:" + uid, Instant.parse("2026-09-21T10:00:00Z").plusSeconds(seq), 0, false);
    }

    @Test
    void duplicateDeliveryIsFlaggedAndCollapsedToOneEffectiveEvent() {
        List<RawEvent> raw = List.of(
                event("e1", "svc", 1, "order:1", null, "OrderCreated"),
                event("e1", "svc", 1, "order:1", null, "OrderCreated"));
        var analysis = AnalysisEngine.analyze(raw, DefaultRules.get(),
                Hypothesis.empty(), null, 8);

        assertThat(analysis.eventSet().anomalies())
                .extracting(Anomaly::kind)
                .contains(Anomaly.Kind.DUPLICATE_DELIVERY);
        assertThat(analysis.eventSet().events()).hasSize(1);
    }

    @Test
    void sameIdentityDifferentContentIsSeqContentConflict() {
        List<RawEvent> raw = List.of(
                new RawEvent(0, 0, "e1", "svc", 1, "order:1", null, "OrderCreated",
                        "h:a", Instant.parse("2026-09-21T10:00:00Z"), 0, false),
                new RawEvent(0, 0, "e1", "svc", 1, "order:1", null, "PaymentAccepted",
                        "h:b", Instant.parse("2026-09-21T10:00:01Z"), 1, false));
        var analysis = AnalysisEngine.analyze(raw, DefaultRules.get(),
                Hypothesis.empty(), null, 8);

        assertThat(analysis.eventSet().anomalies())
                .extracting(Anomaly::kind)
                .contains(Anomaly.Kind.SEQ_CONTENT_CONFLICT);
    }

    @Test
    void missingParentIsReported() {
        List<RawEvent> raw = List.of(event("e2", "svc", 1, "order:1", "e1", "OrderCreated"));
        var analysis = AnalysisEngine.analyze(raw, DefaultRules.get(),
                Hypothesis.empty(), null, 8);
        assertThat(analysis.eventSet().unresolvedParents()).containsExactly("e1");
        assertThat(analysis.eventSet().anomalies())
                .extracting(Anomaly::kind)
                .contains(Anomaly.Kind.MISSING_PARENT);
    }

    @Test
    void placeholderIsSyntheticNeverDrivesStateAndCanResolveMissingParent() {
        List<RawEvent> raw = List.of(
                event("e2", "payment-svc", 1, "order:1", "e1", "PaymentAccepted"));
        Hypothesis hypothesis = new Hypothesis(List.of(),
                List.of(new Hypothesis.PlaceholderRequest("e1", "order:1", "order-svc", 0L)),
                List.of(), "assume parent existed");
        var analysis = AnalysisEngine.analyze(raw, DefaultRules.get(),
                hypothesis, null, 8);

        EffectiveEvent placeholder = analysis.eventSet().events().stream()
                .filter(EffectiveEvent::placeholder).findFirst().orElseThrow();
        assertThat(placeholder.type()).startsWith("PLACEHOLDER:");
        assertThat(analysis.eventSet().unresolvedParents()).doesNotContain("e1");
        var trace = analysis.replay().aggregates().get("order:1");
        assertThat(trace.steps()).hasSize(2);
        assertThat(trace.steps().get(0).placeholder()).isTrue();
        assertThat(trace.steps().get(0).applied()).isFalse();
        assertThat(analysis.replay().placeholderStillOpen()).isTrue();
    }

    @Test
    void invalidTransitionProducesReproducibleMinimalSlice() {
        List<RawEvent> raw = List.of(
                event("e1", "order-svc", 1, "order:1", null, "OrderCreated"),
                event("e2", "payment-svc", 1, "order:1", "e1", "PaymentAccepted"),
                event("e3", "shipping-svc", 1, "order:1", "e2", "Shipped"),
                event("e4", "shipping-svc", 2, "order:1", "e3", "Refunded"));
        var analysis = AnalysisEngine.analyze(raw, DefaultRules.get(),
                Hypothesis.empty(), null, 8);

        ReplayResult.FailureSummary failure = analysis.replay().earliestFailure();
        assertThat(failure).isNotNull();
        assertThat(failure.eventType()).isEqualTo("Refunded");
        ReplayResult.MinimalSlice slice = analysis.replay().minimalSlice();
        assertThat(slice.causalEventUids()).containsExactlyInAnyOrder("e1", "e2", "e3");
        assertThat(slice.steps()).extracting(ReplayResult.SliceStep::eventUid)
                .last().isEqualTo("e4");
    }

    @Test
    void wallClockSkewCannotReorderCausalEvents() {
        RawEvent parent = new RawEvent(0, 0, "parent", "svc-a", 1, "order:1",
                null, "OrderCreated", "h:p", Instant.parse("2026-09-21T10:00:10Z"), 0, false);
        RawEvent child = new RawEvent(0, 0, "child", "svc-b", 1, "order:1",
                "parent", "PaymentAccepted", "h:c", Instant.parse("2026-09-21T10:00:00Z"), 1, false);
        var analysis = AnalysisEngine.analyze(List.of(parent, child), DefaultRules.get(),
                Hypothesis.empty(), null, 8);

        PartialOrder graph = analysis.graph().get("order:1");
        assertThat(graph.reachable("parent", "child")).isTrue();
        assertThat(graph.concurrent("parent", "child")).isFalse();
        assertThat(graph.canonicalTopoSeed()).containsExactly("parent", "child");
    }

    @Test
    void concurrentEventsCanSwapAndEnumerationIsBounded() {
        List<RawEvent> raw = List.of(
                event("a1", "svc-a", 1, "order:1", null, "PaymentAccepted"),
                event("b1", "svc-b", 1, "order:1", null, "PaymentRejected"));
        var analysis = AnalysisEngine.analyze(raw, DefaultRules.get(),
                Hypothesis.empty(), null, 8);
        PartialOrder graph = analysis.graph().get("order:1");

        PartialOrder.Enumeration enumeration = graph.enumerateLinearizations(10);
        assertThat(enumeration.orders()).hasSize(2);
        assertThat(enumeration.truncated()).isFalse();

        PartialOrder.Enumeration capped = graph.enumerateLinearizations(1);
        assertThat(capped.orders()).hasSize(1);
        assertThat(capped.truncated()).isTrue();
    }

    @Test
    void ignoreDeliveryHypothesisRemovesEventWithoutTouchingRawLog() {
        List<RawEvent> raw = List.of(
                event("e1", "svc", 1, "order:1", null, "OrderCreated"),
                event("x9", "svc", 2, "order:1", "e1", "Cancelled"));
        var baseline = AnalysisEngine.analyze(raw, DefaultRules.get(),
                Hypothesis.empty(), null, 8);
        assertThat(baseline.replay().aggregates().get("order:1").currentState())
                .isEqualTo("CANCELLED");

        Hypothesis ignoring = new Hypothesis(List.of("x9"), List.of(), List.of(), null);
        var repaired = AnalysisEngine.analyze(raw, DefaultRules.get(),
                ignoring, null, 8);
        assertThat(repaired.eventSet().ignoredEventUids()).containsExactly("x9");
        assertThat(repaired.replay().aggregates().get("order:1").currentState())
                .isEqualTo("OPEN");
    }

    @Test
    void remappedKeyMovesEventToAnotherAggregate() {
        List<RawEvent> raw = List.of(
                event("e1", "svc", 1, "order:1", null, "OrderCreated"),
                event("e2", "svc", 2, "order:WRONG", "e1", "PaymentAccepted"));
        Hypothesis hypothesis = new Hypothesis(List.of(), List.of(),
                List.of(new Hypothesis.KeyRemap("e2", "order:1")), null);
        var analysis = AnalysisEngine.analyze(raw, DefaultRules.get(),
                hypothesis, null, 8);
        assertThat(analysis.graph()).containsOnlyKeys("order:1");
        assertThat(analysis.replay().aggregates().get("order:1").currentState())
                .isEqualTo("PAID");
    }

    @Test
    void cyclesAreDetected() {
        List<RawEvent> raw = List.of(
                new RawEvent(0, 0, "a", "svc-a", 1, "k", "b", "OrderCreated",
                        "h", Instant.now(), 0, false),
                new RawEvent(0, 0, "b", "svc-b", 1, "k", "a", "PaymentAccepted",
                        "h2", Instant.now(), 1, false));
        var analysis = AnalysisEngine.analyze(raw, DefaultRules.get(),
                Hypothesis.empty(), null, 8);
        assertThat(analysis.replay().aggregates()).doesNotContainKey("k");
        assertThat(analysis.replay().earliestFailure()).isNull();
        assertThatThrownBy(() -> analysis.graph().get("k").canonicalTopoSeed())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }
    @Test
    void fixedOrderMustBeALegalLinearization() {
        List<RawEvent> raw = List.of(
                event("e1", "order-svc", 1, "order:1", null, "OrderCreated"),
                event("e2", "payment-svc", 1, "order:1", "e1", "PaymentAccepted"),
                event("e3", "payment-svc", 2, "order:1", "e2", "Shipped"));

        var legal = AnalysisEngine.analyze(raw, DefaultRules.get(),
                Hypothesis.empty(), List.of("e1", "e2", "e3"), 8);
        assertThat(legal.replay().chosenOrder()).containsExactly("e1", "e2", "e3");

        assertThatThrownBy(() -> AnalysisEngine.analyze(raw, DefaultRules.get(),
                Hypothesis.empty(), List.of("e2", "e1", "e3"), 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("causal edge");
    }
}
