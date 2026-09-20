package com.example.causalworkbench.web;

import com.example.causalworkbench.service.RuleService;
import com.example.causalworkbench.service.WorkbenchService;
import com.example.causalworkbench.web.dto.ApiDtos.CandidateRequest;
import com.example.causalworkbench.web.dto.ApiDtos.IngestRequest;
import com.example.causalworkbench.web.dto.ApiDtos.MergeCandidateRequest;
import com.example.causalworkbench.web.dto.ApiDtos.MergeRequest;
import com.example.causalworkbench.web.dto.ApiDtos.ReplayPreviewRequest;
import com.example.causalworkbench.web.dto.ApiDtos.RuleRequest;
import com.example.causalworkbench.web.dto.ApiDtos.UpdateWorkspaceRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkbenchController {
    private final WorkbenchService workbench;
    private final RuleService ruleService;

    public WorkbenchController(WorkbenchService workbench, RuleService ruleService) {
        this.workbench = workbench;
        this.ruleService = ruleService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/events")
    public Map<String, Object> ingest(@Valid @RequestBody IngestRequest request) {
        return workbench.ingest(request.clientBatchId(), request.events());
    }

    @GetMapping("/events")
    public Map<String, Object> evidence() {
        return workbench.evidence();
    }

    @PostMapping("/replay-preview")
    public Map<String, Object> preview(@Valid @RequestBody ReplayPreviewRequest request) {
        return workbench.preview(request.hypothesis(), request.fixedOrder());
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession() {
        return Map.of("id", workbench.createSessionFromCurrentLog().getId());
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return workbench.sessions();
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> session(@PathVariable String sessionId) {
        return workbench.session(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/derive")
    public Map<String, Object> derive(@PathVariable String sessionId) {
        return workbench.deriveFromSession(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/candidates")
    public Map<String, Object> candidate(@PathVariable String sessionId,
                                         @Valid @RequestBody CandidateRequest request) {
        return workbench.submitCandidate(sessionId, request.name(), request.hypothesis(),
                request.expectedWorkspaceRevision());
    }

    @PostMapping("/sessions/{sessionId}/candidates/{candidateId}/fixed-replay")
    public Map<String, Object> fixedReplay(@PathVariable String sessionId, @PathVariable String candidateId,
                                           @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) body.getOrDefault("fixedOrder", List.of());
        Long revision = body.get("expectedWorkspaceRevision") == null ? null
                : Long.valueOf(body.get("expectedWorkspaceRevision").toString());
        return workbench.replayFixed(sessionId, candidateId, order, revision);
    }

    @PostMapping("/sessions/{sessionId}/merge")
    public Map<String, Object> merge(@PathVariable String sessionId, @Valid @RequestBody MergeRequest request) {
        return workbench.mergeCandidate(sessionId, request.candidateId(), request.expectedWorkspaceRevision(),
                request.candidateRevision(), request.note());
    }

    @PostMapping("/sessions/{sessionId}/workspace")
    public Map<String, Object> updateWorkspace(@PathVariable String sessionId,
                                               @Valid @RequestBody UpdateWorkspaceRequest request) {
        return workbench.updateWorkspace(sessionId, request.status(), request.note(),
                request.expectedWorkspaceRevision());
    }

    @GetMapping("/sessions/{sessionId}/export")
    public Map<String, Object> export(@PathVariable String sessionId) {
        return workbench.exportSession(sessionId);
    }

    @GetMapping("/rules/active")
    public Map<String, Object> activeRule() {
        var rule = ruleService.active();
        return Map.of("code", rule.getCode(), "version", rule.getVersion(),
                "fingerprint", rule.getFingerprint(), "definition", ruleService.parseRules(rule.getDefinitionJson()));
    }

    @PostMapping("/rules")
    public ResponseEntity<Map<String, Object>> createRule(@Valid @RequestBody RuleRequest request) {
        var rule = ruleService.createVersion(request.definitionJson());
        return ResponseEntity.status(201).body(Map.of("code", rule.getCode(), "version", rule.getVersion(),
                "fingerprint", rule.getFingerprint()));
    }
}
