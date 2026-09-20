package com.causaldbg.web;

import com.causaldbg.domain.Hypothesis;

import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record IngestEvent(String uid, String service, long seq, String key,
                              String parent, String type, String payloadHash, String ts) {
    }

    public record IngestRequest(String name, List<IngestEvent> events) {
    }

    public record SessionCreateRequest(String name, Long logId,
                                       String ruleVersion, String ruleJson,
                                       Hypothesis hypothesis) {
    }

    public record SessionUpdateRequest(String name, Hypothesis hypothesis,
                                       String ruleVersion, String ruleJson,
                                       long expectedRevision) {
    }

    public record DeriveRequest(String name, Long newLogId, Hypothesis hypothesis) {
    }

    public record ReplayRequest(Long sessionId, Long logId,
                                String ruleVersion, String ruleJson,
                                Hypothesis hypothesis,
                                List<String> fixedOrder, Integer bound) {
    }
}
