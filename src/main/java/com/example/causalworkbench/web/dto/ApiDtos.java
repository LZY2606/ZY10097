package com.example.causalworkbench.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public final class ApiDtos {
    private ApiDtos() {
    }

    public record IngestRequest(
            String clientBatchId,
            @NotNull @Valid List<EventInput> events
    ) {
    }

    public record EventInput(
            String eventId,
            @NotBlank String service,
            long seq,
            @NotBlank String key,
            @NotBlank String type,
            Object payload,
            String parentId,
            Long timestamp
    ) {
    }

    public record HypothesisInput(
            Boolean ignoreDuplicates,
            Boolean fillMissingParents,
            Map<String, String> keyAdjustments,
            List<String> seedOrder,
            Integer interleavingBound
    ) {
    }

    public record CandidateRequest(
            String name,
            @NotNull @Valid HypothesisInput hypothesis,
            Long expectedWorkspaceRevision
    ) {
    }

    public record ReplayPreviewRequest(
            @NotNull @Valid HypothesisInput hypothesis,
            List<String> fixedOrder
    ) {
    }

    public record MergeCandidateRequest(
            @NotBlank String candidateId,
            Long expectedWorkspaceRevision
    ) {
    }

    public record UpdateWorkspaceRequest(
            @NotBlank String status,
            String note,
            Long expectedWorkspaceRevision
    ) {
    }

    public record MergeRequest(
            @NotBlank String candidateId,
            String note,
            @NotNull Long expectedWorkspaceRevision,
            @NotNull Long candidateRevision
    ) {
    }

    public record RuleRequest(String definitionJson) {
    }
}
