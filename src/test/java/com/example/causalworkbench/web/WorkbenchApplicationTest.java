package com.example.causalworkbench.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.causalworkbench.service.ConflictException;
import com.example.causalworkbench.service.ReplayModels.ReplayResult;
import com.example.causalworkbench.service.WorkbenchService;
import com.example.causalworkbench.web.dto.ApiDtos.EventInput;
import com.example.causalworkbench.web.dto.ApiDtos.HypothesisInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WorkbenchApplicationTest {
    @Autowired
    WorkbenchService service;

    @Test
    void ingestsEvidenceCreatesBoundSessionAndDetectsDuplicatesAndConflicts() {
        service.ingest("batch-1", List.of(
                new EventInput("create", "orders", 1, "a", "CREATE", Map.of(), null, 1000L),
                new EventInput("complete", "billing", 1, "a", "COMPLETE", Map.of(), "create", 900L)));
        Map<String, Object> replay = service.ingest("batch-duplicate", List.of(
                new EventInput("create", "orders", 1, "a", "CREATE", Map.of(), null, 1000L)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) replay.get("events");
        assertThat(events.get(0).get("classification")).isEqualTo("DUPLICATE_DELIVERY");

        String sessionId = service.createSessionFromCurrentLog().getId();
        Map<String, Object> session = service.session(sessionId);
        assertThat(session.get("logFingerprint")).isNotEqualTo("");

        Map<String, Object> candidate = service.submitCandidate(sessionId, "valid",
                new HypothesisInput(true, true, Map.of(), List.of(), 10), 0L);
        assertThat(((ReplayResult) candidate.get("result")).outcome()).isEqualTo("PASSED");

        Map<String, Object> export = service.exportSession(sessionId);
        assertThat(export).containsKeys("minimalSlice", "hypothesis", "deterministicTopologySeed");
    }

    @Test
    void placeholderCandidatesAreInvalidatedWhenRealParentArrivesAndDerivationReplays() {
        service.ingest("missing-parent-batch", List.of(
                new EventInput("child", "orders", 2, "a", "COMPLETE", Map.of(), "parent", null)));
        String oldSessionId = service.createSessionFromCurrentLog().getId();
        Map<String, Object> candidate = service.submitCandidate(oldSessionId, "placeholder",
                new HypothesisInput(true, true, Map.of(), List.of(), 1), 0L);
        String candidateId = (String) candidate.get("id");

        service.ingest("real-parent-batch", List.of(
                new EventInput("parent", "orders", 1, "a", "CREATE", Map.of(), null, null)));
        Map<String, Object> old = service.session(oldSessionId);
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = ((List<Map<String, Object>>) old.get("candidates")).get(0);
        assertThat(stored.get("status")).isEqualTo("INVALIDATED_REAL_PARENT_ARRIVED");

        Map<String, Object> derived = service.deriveFromSession(oldSessionId);
        assertThat(derived.get("parentSessionId")).isEqualTo(oldSessionId);
        @SuppressWarnings("unchecked")
        Map<String, Object> copied = ((List<Map<String, Object>>) derived.get("candidates")).get(0);
        assertThat(copied.get("parentCandidateId")).isEqualTo(candidateId);
        assertThat(copied.get("status")).isEqualTo("REPLAYED_NEW_EVIDENCE");
    }

    @Test
    void twoStaleBrowserMergesReturnCurrentConflictContentForSecondMerge() {
        service.ingest("conflict-batch", List.of(
                new EventInput("e", "orders", 1, "a", "CREATE", Map.of(), null, null)));
        String sessionId = service.createSessionFromCurrentLog().getId();
        Map<String, Object> first = service.submitCandidate(sessionId, "first", hypothesis(), 0L);
        Map<String, Object> second = service.submitCandidate(sessionId, "second", hypothesis(), 0L);

        service.mergeCandidate(sessionId, (String) first.get("id"), 0L, 0L, "browser one");
        assertThatThrownBy(() -> service.mergeCandidate(sessionId, (String) second.get("id"), 0L, 0L,
                "browser two"))
                .isInstanceOf(ConflictException.class)
                .satisfies(error -> {
                    ConflictException conflict = (ConflictException) error;
                    assertThat(conflict.getBody().get("currentRevision")).isEqualTo(1L);
                    assertThat(conflict.getBody().get("selectedCandidateId")).isEqualTo(first.get("id"));
                });

        Map<String, Object> remerged = service.mergeCandidate(sessionId, (String) second.get("id"), 1L, 0L,
                "browser two recombined after viewing conflict");
        assertThat(((Map<?, ?>) remerged.get("workspace")).get("selectedCandidateId")).isEqualTo(second.get("id"));
    }

    private HypothesisInput hypothesis() {
        return new HypothesisInput(true, true, Map.of(), List.of(), 10);
    }
}
