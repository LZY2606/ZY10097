package com.causaldbg.web;

import com.causaldbg.domain.DebugSession;
import com.causaldbg.domain.DefaultRules;
import com.causaldbg.domain.EventLog;
import com.causaldbg.domain.Hypothesis;
import com.causaldbg.domain.RuleSet;
import com.causaldbg.store.ConflictException;
import com.causaldbg.store.LogRepository;
import com.causaldbg.store.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final DebugService service;
    private final LogRepository logRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper mapper;

    public ApiController(DebugService service, LogRepository logRepository,
                         SessionRepository sessionRepository, ObjectMapper mapper) {
        this.service = service;
        this.logRepository = logRepository;
        this.sessionRepository = sessionRepository;
        this.mapper = mapper;
    }

    @GetMapping("/rules")
    public RuleSet defaultRules() {
        return DefaultRules.get();
    }

    @PostMapping("/logs/sample")
    public EventLog loadSample() {
        return service.ingest(new Dtos.IngestRequest("sample-incident", SampleData.events()));
    }

    @PostMapping("/logs")
    public EventLog ingest(@RequestBody Dtos.IngestRequest request) {
        return service.ingest(request);
    }

    @GetMapping("/logs")
    public List<EventLog> logs() {
        return logRepository.findAllLogs();
    }

    @GetMapping("/logs/{id}")
    public Map<String, Object> log(@PathVariable long id) {
        EventLog log = logRepository.findLog(id);
        if (log == null) {
            throw new IllegalArgumentException("unknown log id " + id);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("log", log);
        body.put("events", logRepository.findEvents(id));
        return body;
    }

    @PostMapping("/sessions")
    public DebugSession createSession(@RequestBody Dtos.SessionCreateRequest request) {
        return service.createSession(request);
    }

    @GetMapping("/sessions")
    public List<DebugSession> sessions() {
        return sessionRepository.findAll();
    }

    @GetMapping("/sessions/{id}")
    public DebugSession session(@PathVariable long id) {
        DebugSession session = sessionRepository.findById(id);
        if (session == null) {
            throw new IllegalArgumentException("unknown session id " + id);
        }
        return session;
    }

    @PutMapping("/sessions/{id}")
    public DebugSession updateSession(@PathVariable long id,
                                      @RequestBody Dtos.SessionUpdateRequest request) {
        DebugSession current = sessionRepository.findById(id);
        if (current == null) {
            throw new IllegalArgumentException("unknown session id " + id);
        }
        String ruleVersion = request.ruleVersion() == null
                ? current.ruleVersion() : request.ruleVersion();
        String ruleJson = request.ruleJson() == null
                ? current.ruleJson() : request.ruleJson();
        return sessionRepository.update(id,
                request.name() == null ? current.name() : request.name(),
                request.hypothesis() == null ? current.hypothesis() : request.hypothesis(),
                ruleVersion, ruleJson, request.expectedRevision());
    }

    @PostMapping("/sessions/{id}/derive")
    public DebugSession derive(@PathVariable long id, @RequestBody Dtos.DeriveRequest request) {
        return service.derive(id, request);
    }

    @PostMapping("/replay")
    public Object replay(@RequestBody Dtos.ReplayRequest request) {
        return renderAnalysis(service.analyze(request));
    }

    @GetMapping("/sessions/{id}/replay")
    public Object sessionReplay(@PathVariable long id,
                                Dtos.ReplayRequest query) {
        return renderAnalysis(service.analyze(
                new Dtos.ReplayRequest(id, null, null, null, null,
                        query.fixedOrder(), query.bound())));
    }

    @GetMapping("/sessions/{id}/export")
    public Map<String, Object> export(@PathVariable long id) {
        return service.export(id);
    }

    private Map<String, Object> renderAnalysis(com.causaldbg.engine.AnalysisEngine.Analysis analysis) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anomalies", analysis.eventSet().anomalies());
        body.put("ignoredEventUids", analysis.eventSet().ignoredEventUids());
        body.put("unresolvedParents", analysis.eventSet().unresolvedParents());
        body.put("placeholderParents", analysis.eventSet().placeholderParents());
        body.put("settledPlaceholderParents", analysis.eventSet().settledPlaceholderParents());
        body.put("derivedSlicesInvalidated", !analysis.eventSet().settledPlaceholderParents().isEmpty());

        List<Map<String, Object>> graphs = new java.util.ArrayList<>();
        for (var entry : analysis.graph().entrySet()) {
            Map<String, Object> graph = new LinkedHashMap<>();
            graph.put("key", entry.getKey());
            graph.put("nodes", entry.getValue().nodes());
            graph.put("edges", entry.getValue().edges());
            graph.put("topoSeed", entry.getValue().canonicalTopoSeed());
            graphs.add(graph);
        }
        body.put("graphs", graphs);
        body.put("replay", analysis.replay());
        return body;
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "REVISION_CONFLICT");
        body.put("message", error.getMessage());
        body.put("hint", "reload the session, inspect the other writer's hypothesis, "
                + "then merge and resubmit on the new revision");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BAD_REQUEST");
        body.put("message", error.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
