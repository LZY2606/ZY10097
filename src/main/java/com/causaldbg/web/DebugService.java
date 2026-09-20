package com.causaldbg.web;

import com.causaldbg.domain.DebugSession;
import com.causaldbg.domain.DefaultRules;
import com.causaldbg.domain.EventLog;
import com.causaldbg.domain.Hypothesis;
import com.causaldbg.domain.RawEvent;
import com.causaldbg.domain.RuleSet;
import com.causaldbg.engine.AnalysisEngine;
import com.causaldbg.engine.Fingerprints;
import com.causaldbg.store.LogRepository;
import com.causaldbg.store.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DebugService {

    private final LogRepository logs;
    private final SessionRepository sessions;
    private final ObjectMapper mapper;

    public DebugService(LogRepository logs, SessionRepository sessions, ObjectMapper mapper) {
        this.logs = logs;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    public EventLog ingest(Dtos.IngestRequest request) {
        List<RawEvent> raw = toRaw(request.events());
        String fingerprint = Fingerprints.logFingerprint(raw);
        EventLog existing = logs.findAllLogs().stream()
                .filter(log -> log.fingerprint().equals(fingerprint))
                .findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        return logs.appendLog(request.name() == null ? "log" : request.name(), fingerprint, raw);
    }

    public DebugSession createSession(Dtos.SessionCreateRequest request) {
        EventLog log = logs.findLog(request.logId());
        if (log == null) {
            throw new IllegalArgumentException("unknown log id " + request.logId());
        }
        RuleSet rules = resolveRules(request.ruleVersion(), request.ruleJson());
        String ruleJson = writeRules(rules);
        return sessions.create(
                request.name() == null ? "session" : request.name(),
                log.id(), log.fingerprint(), rules.version(), ruleJson,
                request.hypothesis() == null ? Hypothesis.empty() : request.hypothesis());
    }

    /**
     * Derives a new session from a newer log. Existing sessions are never
     * rebound in place; binding a session to different evidence is only
     * possible by creating a derived session.
     */
    public DebugSession derive(long sessionId, Dtos.DeriveRequest request) {
        DebugSession base = sessions.findById(sessionId);
        if (base == null) {
            throw new IllegalArgumentException("unknown session id " + sessionId);
        }
        EventLog target = logs.findLog(request.newLogId());
        if (target == null) {
            throw new IllegalArgumentException("unknown log id " + request.newLogId());
        }
        String name = request.name() == null
                ? base.name() + " @log" + target.id() : request.name();
        Hypothesis hypothesis = request.hypothesis() == null ? base.hypothesis() : request.hypothesis();
        return sessions.create(name, target.id(), target.fingerprint(),
                base.ruleVersion(), base.ruleJson(), hypothesis);
    }

    public AnalysisEngine.Analysis analyze(Dtos.ReplayRequest request) {
        RuleSet rules;
        List<RawEvent> raw;
        if (request.sessionId() != null) {
            DebugSession session = sessions.findById(request.sessionId());
            if (session == null) {
                throw new IllegalArgumentException("unknown session id " + request.sessionId());
            }
            rules = readRules(session.ruleJson());
            raw = logs.findEvents(session.logId());
            Hypothesis hypothesis = request.hypothesis() != null
                    ? request.hypothesis() : session.hypothesis();
            int bound = request.bound() == null ? 64 : request.bound();
            return AnalysisEngine.analyze(raw, rules, hypothesis, request.fixedOrder(), bound);
        }
        if (request.logId() == null) {
            throw new IllegalArgumentException("sessionId or logId required");
        }
        raw = logs.findEvents(request.logId());
        rules = resolveRules(request.ruleVersion(), request.ruleJson());
        int bound = request.bound() == null ? 64 : request.bound();
        return AnalysisEngine.analyze(raw, rules,
                request.hypothesis() == null ? Hypothesis.empty() : request.hypothesis(),
                request.fixedOrder(), bound);
    }

    public Map<String, Object> export(long sessionId) {
        DebugSession session = sessions.findById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("unknown session id " + sessionId);
        }
        List<RawEvent> raw = logs.findEvents(session.logId());
        RuleSet rules = readRules(session.ruleJson());
        AnalysisEngine.Analysis analysis = AnalysisEngine.analyze(
                raw, rules, session.hypothesis(), null, 64);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("exportVersion", 1);
        document.put("sessionId", session.id());
        document.put("sessionName", session.name());
        document.put("revision", session.revision());
        document.put("ruleVersion", session.ruleVersion());
        document.put("ruleFingerprint", Fingerprints.ruleFingerprint(rules));
        document.put("logFingerprint", session.logFingerprint());
        document.put("hypothesis", session.hypothesis());
        document.put("hypothesisFingerprint",
                Fingerprints.hypothesisFingerprint(session.hypothesis()));
        document.put("topologySeed", analysis.replay().chosenOrder());
        document.put("topologySeedDescription", analysis.replay().topoSeedDescription());
        document.put("minimalSlice", analysis.replay().minimalSlice());
        document.put("earliestFailure", analysis.replay().earliestFailure());
        document.put("concurrentPairs", analysis.replay().concurrentPairs());
        document.put("anomalies", analysis.eventSet().anomalies());
        document.put("settledPlaceholderParents", analysis.eventSet().settledPlaceholderParents());
        document.put("derivedSlicesInvalidated", !analysis.eventSet().settledPlaceholderParents().isEmpty());
        document.put("invalidated", session.invalidated());
        document.put("invalidationReason", session.invalidationReason());
        return document;
    }

    public RuleSet resolveRules(String version, String ruleJson) {
        if (ruleJson != null && !ruleJson.isBlank()) {
            RuleSet custom = readRules(ruleJson);
            return custom;
        }
        if (version == null || version.isBlank()
                || version.equals(DefaultRules.VERSION)) {
            return DefaultRules.get();
        }
        throw new IllegalArgumentException("unknown rule version " + version
                + "; provide ruleJson for custom rules");
    }

    private RuleSet readRules(String json) {
        try {
            return mapper.readValue(json, RuleSet.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid rule json: " + e.getMessage(), e);
        }
    }

    private String writeRules(RuleSet rules) {
        try {
            return mapper.writeValueAsString(rules);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<RawEvent> toRaw(List<Dtos.IngestEvent> events) {
        List<RawEvent> result = new ArrayList<>();
        long index = 0;
        for (Dtos.IngestEvent event : events) {
            Instant ts = null;
            if (event.ts() != null && !event.ts().isBlank()) {
                ts = Instant.parse(event.ts());
            }
            result.add(new RawEvent(0, 0, event.uid(), event.service(), event.seq(),
                    event.key(), blankToNull(event.parent()), event.type(),
                    event.payloadHash(), ts, index, false));
            index++;
        }
        return result;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
