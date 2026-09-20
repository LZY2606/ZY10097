package com.example.causalworkbench.service;

import com.example.causalworkbench.domain.Candidate;
import com.example.causalworkbench.domain.DebugSession;
import com.example.causalworkbench.domain.IngestionBatch;
import com.example.causalworkbench.domain.RawEvent;
import com.example.causalworkbench.domain.RuleSet;
import com.example.causalworkbench.domain.Workspace;
import com.example.causalworkbench.repo.CandidateRepository;
import com.example.causalworkbench.repo.DebugSessionRepository;
import com.example.causalworkbench.repo.IngestionBatchRepository;
import com.example.causalworkbench.repo.RawEventRepository;
import com.example.causalworkbench.repo.WorkspaceRepository;
import com.example.causalworkbench.service.ReplayModels.EventNode;
import com.example.causalworkbench.service.ReplayModels.Hypothesis;
import com.example.causalworkbench.service.ReplayModels.ReplayRequest;
import com.example.causalworkbench.service.ReplayModels.ReplayResult;
import com.example.causalworkbench.service.ReplayModels.WorkbenchRules;
import com.example.causalworkbench.web.dto.ApiDtos.EventInput;
import com.example.causalworkbench.web.dto.ApiDtos.HypothesisInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkbenchService {
    private final RawEventRepository rawEventRepository;
    private final IngestionBatchRepository batchRepository;
    private final DebugSessionRepository sessionRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CandidateRepository candidateRepository;
    private final RuleService ruleService;
    private final ReplayEngine replayEngine;
    private final ObjectMapper objectMapper;
    private final Hashes hashes;

    public WorkbenchService(RawEventRepository rawEventRepository, IngestionBatchRepository batchRepository,
                            DebugSessionRepository sessionRepository, WorkspaceRepository workspaceRepository,
                            CandidateRepository candidateRepository, RuleService ruleService,
                            ReplayEngine replayEngine, ObjectMapper objectMapper, Hashes hashes) {
        this.rawEventRepository = rawEventRepository;
        this.batchRepository = batchRepository;
        this.sessionRepository = sessionRepository;
        this.workspaceRepository = workspaceRepository;
        this.candidateRepository = candidateRepository;
        this.ruleService = ruleService;
        this.replayEngine = replayEngine;
        this.objectMapper = objectMapper;
        this.hashes = hashes;
    }

    @Transactional
    public Map<String, Object> ingest(String clientBatchId, List<EventInput> inputs) {
        String batchId = Optional.ofNullable(clientBatchId).filter(value -> !value.isBlank())
                .orElse("batch-" + UUID.randomUUID());
        if (batchRepository.existsById(batchId)) {
            throw new ConflictException("batch already received", Map.of("batchId", batchId,
                    "message", "this idempotency key already exists; raw evidence was not rewritten"));
        }
        String receivedAt = Instant.now().toString();
        List<Map<String, Object>> reports = new ArrayList<>();
        long startOrder = rawEventRepository.count() + 1;
        for (int index = 0; index < inputs.size(); index++) {
            EventInput input = inputs.get(index);
            String rawJson = hashes.canonical(input);
            String receiptId = "receipt-" + UUID.randomUUID();
            String eventId = eventId(input);
            String payloadJson = payload(input);
            String contentHash = hashes.sha256(String.join("|",
                    input.service(), Long.toString(input.seq()), input.key(), input.type(), payloadJson,
                    nullToEmpty(input.parentId())));
            List<RawEvent> priorIdentity = rawEventRepository.findByEventIdOrderByReceivedOrderAsc(eventId);
            String classification;
            String reason;
            boolean materialized;
            if (priorIdentity.isEmpty()) {
                classification = "NEW";
                reason = "first receipt for event identity " + eventId;
                materialized = true;
            } else {
                RawEvent first = priorIdentity.get(0);
                if (Objects.equals(first.getContentHash(), contentHash) && Objects.equals(first.getRawJson(), rawJson)) {
                    classification = "DUPLICATE_DELIVERY";
                    reason = "byte-identical repeat of receipt " + first.getReceiptId();
                    materialized = false;
                } else {
                    classification = "SAME_ID_CONTENT_CONFLICT";
                    reason = "same event identity has different content; original receipt "
                            + first.getReceiptId() + " remains immutable";
                    materialized = true;
                }
            }
            RawEvent raw = new RawEvent(receiptId, batchId, startOrder + index, receivedAt, eventId,
                    input.service(), input.seq(), input.key(), input.type(), payloadJson,
                    blankToNull(input.parentId()), input.timestamp(), rawJson, contentHash, classification, reason,
                    materialized);
            rawEventRepository.save(raw);
            reports.add(Map.of("receiptId", receiptId, "eventId", eventId, "classification", classification,
                    "materialized", materialized, "reason", reason));
        }
        String requestFingerprint = hashes.fingerprint(reports);
        String summaryJson = hashes.canonical(reports);
        batchRepository.save(new IngestionBatch(batchId, receivedAt, requestFingerprint, summaryJson));
        invalidateCandidatesForNewEvidence(receivedAt);
        return Map.of("batchId", batchId, "receivedAt", receivedAt, "events", reports,
                "logFingerprint", currentLogFingerprint());
    }

    @Transactional(readOnly = true)
    public List<RawEvent> rawReceipts() {
        return rawEventRepository.findAllByOrderByReceivedOrderAsc();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> evidence() {
        List<Map<String, Object>> receipts = rawEventRepository.findAllByOrderByReceivedOrderAsc().stream()
                .map(this::rawView).toList();
        return Map.of("fingerprint", currentLogFingerprint(), "receiptCount", receipts.size(),
                "receipts", receipts);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(HypothesisInput input, List<String> fixedOrder) {
        RuleSet rule = ruleService.active();
        ReplayResult result = replay(hypothesis(input), rule, fixedOrder);
        return resultView(null, rule, result, hypothesis(input), currentLogFingerprint(), null);
    }

    @Transactional
    public DebugSession createSessionFromCurrentLog() {
        RuleSet rule = ruleService.active();
        List<EventNode> nodes = materializedNodes();
        ReplayResult baseline = replayEngine.replay(new ReplayRequest(ruleService.parseRules(rule.getDefinitionJson()),
                nodes, defaultHypothesis()));
        String id = "sess-" + UUID.randomUUID();
        String createdAt = Instant.now().toString();
        String graph = hashes.canonical(Map.of("nodes", nodes, "baseline", baseline));
        DebugSession session = new DebugSession(id, null, createdAt, currentLogFingerprint(), rule.getCode(),
                rule.getVersion(), rule.getFingerprint(), graph, rawEventRepository.count());
        sessionRepository.save(session);
        workspaceRepository.save(new Workspace(id, "OPEN", null,
                "session pinned to rule v" + rule.getVersion() + " and log fingerprint", createdAt));
        return session;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> session(String sessionId) {
        DebugSession session = getSession(sessionId);
        Workspace workspace = getWorkspace(sessionId);
        List<Map<String, Object>> candidates = candidateRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId).stream().map(this::candidateView).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", session.getId());
        response.put("parentSessionId", nullToEmpty(session.getParentSessionId()));
        response.put("createdAt", session.getCreatedAt());
        response.put("logFingerprint", session.getLogFingerprint());
        response.put("currentLogFingerprint", currentLogFingerprint());
        response.put("ruleCode", session.getRuleCode());
        response.put("ruleVersion", session.getRuleVersion());
        response.put("ruleFingerprint", session.getRuleFingerprint());
        response.put("eventReceiptCount", session.getEventReceiptCount());
        response.put("workspace", workspaceView(workspace));
        response.put("candidates", candidates);
        return response;
    }

    @Transactional
    public Map<String, Object> submitCandidate(String sessionId, String name, HypothesisInput input,
                                               Long expectedRevision) {
        DebugSession session = getSession(sessionId);
        Workspace workspace = getWorkspace(sessionId);
        checkRevision(workspace, expectedRevision);
        RuleSet rule = getSessionRules(session);
        Hypothesis hypothesis = hypothesis(input);
        ReplayResult result = replayForSession(session, hypothesis, null);
        Candidate candidate = new Candidate("cand-" + UUID.randomUUID(), sessionId, null, Instant.now().toString(),
                hashes.canonical(Map.of("name", nullToEmpty(name), "hypothesis", hypothesis)),
                rule.getFingerprint(), session.getLogFingerprint(), writeJson(result),
                "FAILED".equals(result.outcome()) ? "DIAGNOSED" : "VERIFIED", null);
        candidateRepository.save(candidate);
        return candidateView(candidate);
    }

    @Transactional
    public Map<String, Object> replayFixed(String sessionId, String candidateId, List<String> fixedOrder,
                                           Long expectedRevision) {
        DebugSession session = getSession(sessionId);
        Workspace workspace = getWorkspace(sessionId);
        checkRevision(workspace, expectedRevision);
        Candidate candidate = getCandidate(candidateId);
        if (!candidate.getSessionId().equals(sessionId)) {
            throw new BadRequestException("candidate belongs to another session");
        }
        Hypothesis hypothesis = readJson(candidate.getHypothesisJson(), Map.class)
                .containsKey("hypothesis") ? readHypothesis(candidate.getHypothesisJson()) : defaultHypothesis();
        ReplayResult result = replayForSession(session, hypothesis, fixedOrder);
        Candidate fixed = new Candidate("cand-" + UUID.randomUUID(), sessionId, candidate.getId(),
                Instant.now().toString(), candidate.getHypothesisJson(), candidate.getRuleFingerprint(),
                candidate.getSourceLogFingerprint(), writeJson(result), "REPLAYED_FIXED",
                "fixed-topology child candidate; parent candidate remains immutable");
        candidateRepository.save(fixed);
        return candidateView(fixed);
    }

    @Transactional
    public Map<String, Object> mergeCandidate(String sessionId, String candidateId, Long expectedWorkspaceRevision,
                                              Long candidateRevision, String note) {
        Workspace workspace = getWorkspace(sessionId);
        Candidate candidate = getCandidate(candidateId);
        if (!candidate.getSessionId().equals(sessionId)) {
            throw new BadRequestException("candidate belongs to another session");
        }
        checkRevision(workspace, expectedWorkspaceRevision);
        if (candidateRevision != null && candidate.getRevision() != candidateRevision) {
            throw conflict("Candidate changed", candidateConflict(candidate, workspace));
        }
        String now = Instant.now().toString();
        workspace.update("MERGED", candidateId, note, now);
        workspaceRepository.save(workspace);
        candidate.mark("SELECTED", null, candidate.getResultJson());
        candidateRepository.save(candidate);
        return session(sessionId);
    }

    @Transactional
    public Map<String, Object> updateWorkspace(String sessionId, String status, String note, Long expectedRevision) {
        Workspace workspace = getWorkspace(sessionId);
        checkRevision(workspace, expectedRevision);
        workspace.update(status, workspace.getSelectedCandidateId(), note, Instant.now().toString());
        workspaceRepository.save(workspace);
        return workspaceView(workspace);
    }

    @Transactional
    public Map<String, Object> deriveFromSession(String parentSessionId) {
        DebugSession parent = getSession(parentSessionId);
        RuleSet rule = ruleService.active();
        if (!Objects.equals(rule.getFingerprint(), parent.getRuleFingerprint())) {
            throw new BadRequestException("active rule fingerprint differs from parent session; create a rule-bound session instead");
        }
        String id = "sess-" + UUID.randomUUID();
        String now = Instant.now().toString();
        List<EventNode> nodes = materializedNodes();
        ReplayResult baseline = replayEngine.replay(new ReplayRequest(ruleService.parseRules(rule.getDefinitionJson()),
                nodes, defaultHypothesis()));
        String graph = hashes.canonical(Map.of("nodes", nodes, "baseline", baseline));
        DebugSession child = new DebugSession(id, parentSessionId, now, currentLogFingerprint(), rule.getCode(),
                rule.getVersion(), rule.getFingerprint(), graph, rawEventRepository.count());
        sessionRepository.save(child);
        workspaceRepository.save(new Workspace(id, "OPEN", null, "derived from " + parentSessionId, now));
        cloneCandidates(parent, child, now);
        return session(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportSession(String sessionId) {
        DebugSession session = getSession(sessionId);
        Workspace workspace = getWorkspace(sessionId);
        List<Candidate> candidates = candidateRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        Candidate selected = candidates.stream()
                .filter(candidate -> Objects.equals(candidate.getId(), workspace.getSelectedCandidateId()))
                .findFirst().orElse(candidates.isEmpty() ? null : candidates.get(candidates.size() - 1));
        ReplayResult result = selected == null ? null : readJson(selected.getResultJson(), ReplayResult.class);
        return Map.of(
                "sessionId", sessionId,
                "logFingerprint", session.getLogFingerprint(),
                "rule", Map.of("code", session.getRuleCode(), "version", session.getRuleVersion(),
                        "fingerprint", session.getRuleFingerprint()),
                "minimalSlice", result == null ? List.of() : result.minimalSlice(),
                "hypothesis", selected == null ? defaultHypothesis() : readHypothesis(selected.getHypothesisJson()),
                "deterministicTopologySeed", result == null ? "" : result.deterministicSeed(),
                "exportedAt", Instant.now().toString());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sessions() {
        return sessionRepository.findAll().stream().map(session -> {
            Workspace workspace = workspaceRepository.findById(session.getId()).orElse(null);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", session.getId());
            view.put("parentSessionId", session.getParentSessionId());
            view.put("createdAt", session.getCreatedAt());
            view.put("logFingerprint", session.getLogFingerprint());
            view.put("ruleVersion", session.getRuleVersion());
            view.put("status", workspace == null ? null : workspace.getStatus());
            return view;
        }).toList();
    }

    private void invalidateCandidatesForNewEvidence(String now) {
        String fingerprint = currentLogFingerprint();
        List<RawEvent> receipts = rawEventRepository.findAllByOrderByReceivedOrderAsc();
        Set<String> realParentIds = new LinkedHashSet<>();
        for (RawEvent raw : receipts) {
            if (raw.isMaterialized() && raw.getParentEventId() != null) {
                realParentIds.add(raw.getParentEventId());
            }
        }
        Map<String, List<RawEvent>> bySessionCandidate = new HashMap<>();
        for (Candidate candidate : candidateRepository.findAll()) {
            if (Objects.equals(candidate.getSourceLogFingerprint(), fingerprint)) {
                continue;
            }
            String reason;
            String status;
            ReplayResult oldResult = readJson(candidate.getResultJson(), ReplayResult.class);
            boolean placeholderResolved = oldResult.minimalSlice().stream()
                    .anyMatch(node -> node.eventId().startsWith("placeholder:")
                            && realParentIds.contains(node.eventId().substring("placeholder:".length())));
            if (placeholderResolved) {
                status = "INVALIDATED_REAL_PARENT_ARRIVED";
                reason = "a real event replaced a placeholder used by this slice; derive a new log version and replay";
            } else {
                status = "SUPERSEDED_NEW_LOG";
                reason = "new immutable receipts arrived; this result remains valid only for its stored fingerprint";
            }
            candidate.mark(status, reason, candidate.getResultJson());
            candidateRepository.save(candidate);
        }
    }

    private void cloneCandidates(DebugSession parent, DebugSession child, String now) {
        for (Candidate old : candidateRepository.findBySessionIdOrderByCreatedAtAsc(parent.getId())) {
            String copiedId = "cand-" + UUID.randomUUID();
            String status;
            String reason;
            if ("INVALIDATED_REAL_PARENT_ARRIVED".equals(old.getStatus())) {
                status = "REPLAYED_NEW_EVIDENCE";
                reason = "placeholder resolved in derived log";
            } else if ("SUPERSEDED_NEW_LOG".equals(old.getStatus())) {
                status = "REPLAYED_NEW_EVIDENCE";
                reason = "copied after new log evidence";
            } else {
                status = "DERIVED";
                reason = "copied from parent candidate; fingerprint identifies derived log";
            }
            Hypothesis hypothesis = readHypothesis(old.getHypothesisJson());
            ReplayResult result = replayForSession(child, hypothesis, null);
            Candidate copy = new Candidate(copiedId, child.getId(), old.getId(), now, old.getHypothesisJson(),
                    child.getRuleFingerprint(), child.getLogFingerprint(), writeJson(result), status, reason);
            candidateRepository.save(copy);
        }
    }

    private ReplayResult replayForSession(DebugSession session, Hypothesis hypothesis, List<String> fixedOrder) {
        RuleSet rule = getSessionRules(session);
        List<EventNode> nodes = nodesForSession(session);
        ReplayRequest request = new ReplayRequest(ruleService.parseRules(rule.getDefinitionJson()), nodes, hypothesis);
        return fixedOrder == null ? replayEngine.replay(request) : replayEngine.replay(request, fixedOrder);
    }

    private ReplayResult replay(Hypothesis hypothesis, RuleSet rule, List<String> fixedOrder) {
        ReplayRequest request = new ReplayRequest(ruleService.parseRules(rule.getDefinitionJson()),
                materializedNodes(), hypothesis);
        return fixedOrder == null ? replayEngine.replay(request) : replayEngine.replay(request, fixedOrder);
    }

    private List<EventNode> nodesForSession(DebugSession session) {
        if (Objects.equals(session.getLogFingerprint(), currentLogFingerprint())) {
            return materializedNodes();
        }
        return readGraphNodes(session);
    }

    @SuppressWarnings("unchecked")
    private List<EventNode> readGraphNodes(DebugSession session) {
        Map<String, Object> graph = readJson(session.getBaselineGraphJson(), Map.class);
        return objectMapper.convertValue(graph.get("nodes"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, EventNode.class));
    }

    private List<EventNode> materializedNodes() {
        Map<String, RawEvent> firstByIdentity = new LinkedHashMap<>();
        Map<String, List<String>> duplicateReceipts = new LinkedHashMap<>();
        for (RawEvent raw : rawEventRepository.findAllByOrderByReceivedOrderAsc()) {
            if (!raw.isMaterialized()) {
                continue;
            }
            RawEvent first = firstByIdentity.get(raw.getEventId());
            if (first == null) {
                firstByIdentity.put(raw.getEventId(), raw);
            } else if (Objects.equals(first.getContentHash(), raw.getContentHash())
                    && Objects.equals(first.getAggregateKey(), raw.getAggregateKey())
                    && Objects.equals(first.getEventType(), raw.getEventType())) {
                duplicateReceipts.computeIfAbsent(raw.getEventId(), ignored -> new ArrayList<>())
                        .add(raw.getReceiptId());
            }
        }
        List<EventNode> nodes = new ArrayList<>();
        for (RawEvent raw : rawEventRepository.findAllByOrderByReceivedOrderAsc()) {
            if (!raw.isMaterialized()) {
                continue;
            }
            RawEvent first = firstByIdentity.get(raw.getEventId());
            boolean canonical = Objects.equals(first.getReceiptId(), raw.getReceiptId());
            String nodeId = canonical ? raw.getEventId() : "conflict:" + raw.getReceiptId();
            List<String> warnings = canonical ? List.of()
                    : List.of("same event identity arrived with different content; receipt retained as conflicting event");
            nodes.add(new EventNode(nodeId, raw.getServiceName(), raw.getSeqNo(),
                    raw.getAggregateKey(), raw.getAggregateKey(), raw.getEventType(), raw.getPayloadJson(),
                    raw.getParentEventId(), raw.getWallClockMillis(), raw.getReceiptId(),
                    raw.getContentHash(), false, canonical ? null : raw.getEventId(),
                    canonical ? List.copyOf(duplicateReceipts.getOrDefault(raw.getEventId(), List.of())) : List.of(),
                    warnings));
        }
        return nodes;
    }

    private RuleSet getSessionRules(DebugSession session) {
        RuleSet active = ruleService.active();
        if (Objects.equals(active.getFingerprint(), session.getRuleFingerprint())) {
            return active;
        }
        throw new BadRequestException("session is bound to rule fingerprint " + session.getRuleFingerprint()
                + "; create a new rule version and derived session instead of replaying it under new rules");
    }

    private DebugSession getSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("debug session not found: " + sessionId));
    }

    private Workspace getWorkspace(String sessionId) {
        return workspaceRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("workspace not found: " + sessionId));
    }

    private Candidate getCandidate(String candidateId) {
        return candidateRepository.findById(candidateId)
                .orElseThrow(() -> new NotFoundException("candidate not found: " + candidateId));
    }

    private void checkRevision(Workspace workspace, Long expectedRevision) {
        if (expectedRevision != null && workspace.getRevision() != expectedRevision) {
            throw conflict("Workspace changed", Map.of(
                    "message", "another browser updated this workspace; inspect the conflict and merge again",
                    "expectedRevision", expectedRevision,
                    "currentRevision", workspace.getRevision(),
                    "currentStatus", workspace.getStatus(),
                    "selectedCandidateId", nullToEmpty(workspace.getSelectedCandidateId()),
                    "currentNote", nullToEmpty(workspace.getNote())));
        }
    }

    private Map<String, Object> candidateConflict(Candidate candidate, Workspace workspace) {
        return Map.of("message", "candidate was modified since your page loaded; reload before merging",
                "candidateId", candidate.getId(), "currentCandidateRevision", candidate.getRevision(),
                "currentStatus", candidate.getStatus(), "workspaceRevision", workspace.getRevision());
    }

    private ConflictException conflict(String message, Map<String, Object> body) {
        return new ConflictException(message, body);
    }

    private Hypothesis hypothesis(HypothesisInput input) {
        HypothesisInput safe = input == null
                ? new HypothesisInput(null, null, Map.of(), List.of(), null)
                : input;
        return new Hypothesis(
                !Boolean.FALSE.equals(safe.ignoreDuplicates()),
                !Boolean.FALSE.equals(safe.fillMissingParents()),
                safe.keyAdjustments() == null ? Map.of() : Map.copyOf(safe.keyAdjustments()),
                safe.seedOrder() == null ? List.of() : List.copyOf(safe.seedOrder()),
                safe.interleavingBound());
    }

    private Hypothesis defaultHypothesis() {
        return new Hypothesis(true, true, Map.of(), List.of(), 100);
    }

    private Hypothesis readHypothesis(String json) {
        Map<?, ?> wrapper = readJson(json, Map.class);
        Object value = wrapper.get("hypothesis");
        return objectMapper.convertValue(value, Hypothesis.class);
    }

    private String currentLogFingerprint() {
        List<String> canonicalReceipts = rawEventRepository.findAllByOrderByReceivedOrderAsc().stream()
                .map(raw -> String.join("|", raw.getReceiptId(), raw.getEventId(), raw.getServiceName(),
                        Long.toString(raw.getSeqNo()), raw.getAggregateKey(), raw.getEventType(),
                        raw.getContentHash(), raw.getClassification()))
                .toList();
        return hashes.sha256(String.join("\n", canonicalReceipts));
    }

    private String eventId(EventInput input) {
        if (input.eventId() != null && !input.eventId().isBlank()) {
            return input.eventId();
        }
        return input.service() + "#" + input.seq();
    }

    private String payload(EventInput input) {
        return writeJson(input.payload() == null ? Map.of() : input.payload());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> rawView(RawEvent raw) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("receiptId", raw.getReceiptId());
        view.put("batchId", raw.getBatchId());
        view.put("receivedOrder", raw.getReceivedOrder());
        view.put("receivedAt", raw.getReceivedAt());
        view.put("eventId", raw.getEventId());
        view.put("service", raw.getServiceName());
        view.put("seq", raw.getSeqNo());
        view.put("key", raw.getAggregateKey());
        view.put("type", raw.getEventType());
        view.put("parentId", nullToEmpty(raw.getParentEventId()));
        view.put("timestamp", raw.getWallClockMillis());
        view.put("payload", readJson(raw.getPayloadJson(), Object.class));
        view.put("contentHash", raw.getContentHash());
        view.put("classification", raw.getClassification());
        view.put("classificationReason", raw.getClassificationReason());
        view.put("materialized", raw.isMaterialized());
        return view;
    }

    private Map<String, Object> workspaceView(Workspace workspace) {
        return Map.of("revision", workspace.getRevision(), "status", workspace.getStatus(),
                "selectedCandidateId", nullToEmpty(workspace.getSelectedCandidateId()),
                "note", nullToEmpty(workspace.getNote()), "updatedAt", workspace.getUpdatedAt());
    }

    private Map<String, Object> candidateView(Candidate candidate) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", candidate.getId());
        view.put("sessionId", candidate.getSessionId());
        view.put("parentCandidateId", nullToEmpty(candidate.getParentCandidateId()));
        view.put("createdAt", candidate.getCreatedAt());
        view.put("status", candidate.getStatus());
        view.put("invalidationReason", nullToEmpty(candidate.getInvalidationReason()));
        view.put("revision", candidate.getRevision());
        view.put("ruleFingerprint", candidate.getRuleFingerprint());
        view.put("sourceLogFingerprint", candidate.getSourceLogFingerprint());
        view.put("hypothesis", readJson(candidate.getHypothesisJson(), Object.class));
        view.put("result", readJson(candidate.getResultJson(), ReplayResult.class));
        return view;
    }

    private Map<String, Object> resultView(String candidateId, RuleSet rule, ReplayResult result,
                                           Hypothesis hypothesis, String logFingerprint, String status) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("candidateId", candidateId);
        view.put("status", status);
        view.put("rule", Map.of("code", rule.getCode(), "version", rule.getVersion(),
                "fingerprint", rule.getFingerprint()));
        view.put("logFingerprint", logFingerprint);
        view.put("hypothesis", hypothesis);
        view.put("result", result);
        return view;
    }
}
