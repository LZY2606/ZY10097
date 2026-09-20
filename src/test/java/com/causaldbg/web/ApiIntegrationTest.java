package com.causaldbg.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:itdb;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper mapper;

    private long ingestSimpleLog() throws Exception {
        var events = List.of(
                new Dtos.IngestEvent("e1", "order-svc", 1, "order:9", null,
                        "OrderCreated", "h1", "2026-09-21T10:00:00Z"),
                new Dtos.IngestEvent("e2", "payment-svc", 1, "order:9", "e1",
                        "PaymentAccepted", "h2", "2026-09-21T10:00:02Z"));
        String body = mapper.writeValueAsString(
                Map.of("name", "it-log", "events", events));
        String response = mockMvc.perform(post("/api/logs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("id").asLong();
    }

    private long createSession(long logId) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "name", "it-session", "logId", logId));
        String response = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("id").asLong();
    }

    @Test
    void ingestsReplaysAndExports() throws Exception {
        long logId = ingestSimpleLog();
        long sessionId = createSession(logId);

        mockMvc.perform(get("/api/sessions/" + sessionId + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay.earliestFailure").doesNotExist())
                .andExpect(jsonPath("$.graphs[0].nodes[0].uid").exists());

        mockMvc.perform(get("/api/sessions/" + sessionId + "/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value("rules-v1"))
                .andExpect(jsonPath("$.logFingerprint").isNotEmpty())
                .andExpect(jsonPath("$.topologySeed").isArray());
    }

    @Test
    void duplicateAndMissingParentAndLateDeliveryAreClassified() throws Exception {
        var events = List.of(
                new Dtos.IngestEvent("e1", "order-svc", 1, "order:9", null,
                        "OrderCreated", "h1", "2026-09-21T10:00:00Z"),
                new Dtos.IngestEvent("e3", "order-svc", 3, "order:9", "e1",
                        "Cancelled", "h3", "2026-09-21T10:00:03Z"),
                new Dtos.IngestEvent("e2", "order-svc", 2, "order:9", "e1",
                        "PaymentAccepted", "h2", "2026-09-21T10:00:02Z"),
                new Dtos.IngestEvent("e3", "order-svc", 3, "order:9", "e1",
                        "Cancelled", "h3", "2026-09-21T10:00:03Z"),
                new Dtos.IngestEvent("e9", "payment-svc", 1, "order:9", "e-missing",
                        "PaymentAccepted", "h9", "2026-09-21T10:00:09Z"));
        String body = mapper.writeValueAsString(Map.of("events", events));
        JsonNode response = mapper.readTree(mockMvc.perform(post("/api/logs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long logId = response.get("id").asLong();

        mockMvc.perform(post("/api/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("logId", logId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anomalies[*].kind").value(
                        org.hamcrest.Matchers.hasItems("LATE_DELIVERY",
                                "DUPLICATE_DELIVERY", "MISSING_PARENT")))
                .andExpect(jsonPath("$.unresolvedParents[0]").value("e-missing"));
    }

    @Test
    void laterWriterOnStaleRevisionSeesConflictAndCanRebase() throws Exception {
        long logId = ingestSimpleLog();
        long sessionId = createSession(logId);

        var hypothesisA = new com.causaldbg.domain.Hypothesis(
                List.of(), List.of(), List.of(), "browser A");
        String updateA = mapper.writeValueAsString(Map.of(
                "name", "renamed-by-A",
                "hypothesis", hypothesisA,
                "expectedRevision", 0));
        mockMvc.perform(put("/api/sessions/" + sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(updateA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        var hypothesisB = new com.causaldbg.domain.Hypothesis(
                List.of("e2"), List.of(), List.of(), "browser B");
        String staleUpdateB = mapper.writeValueAsString(Map.of(
                "name", "renamed-by-B",
                "hypothesis", hypothesisB,
                "expectedRevision", 0));
        mockMvc.perform(put("/api/sessions/" + sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(staleUpdateB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REVISION_CONFLICT"));

        String rebasedB = mapper.writeValueAsString(Map.of(
                "name", "merged-by-B",
                "hypothesis", hypothesisB,
                "expectedRevision", 1));
        mockMvc.perform(put("/api/sessions/" + sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(rebasedB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.name").value("merged-by-B"));
    }

    @Test
    void newLogOnlyDerivesNewSessionAndPlaceholderHypothesisReplays() throws Exception {
        long logId = ingestSimpleLog();
        long sessionId = createSession(logId);

        var v2Events = List.of(
                new Dtos.IngestEvent("e1", "order-svc", 1, "order:9", null,
                        "OrderCreated", "h1", "2026-09-21T10:00:00Z"),
                new Dtos.IngestEvent("e2", "payment-svc", 1, "order:9", "e1",
                        "PaymentAccepted", "h2", "2026-09-21T10:00:02Z"),
                new Dtos.IngestEvent("e3", "shipping-svc", 1, "order:9", "e2",
                        "Shipped", "h3", "2026-09-21T10:00:04Z"));
        long newLogId = mapper.readTree(mockMvc.perform(post("/api/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("events", v2Events))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(post("/api/sessions/" + sessionId + "/derive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "derived", "newLogId", newLogId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logId").value(newLogId))
                .andExpect(jsonPath("$.revision").value(0));

        var placeholder = new com.causaldbg.domain.Hypothesis(
                List.of(),
                List.of(new com.causaldbg.domain.Hypothesis.PlaceholderRequest(
                        "ghost", "order:9", "order-svc", 0L)),
                List.of(), null);
        String replay = mapper.writeValueAsString(new Dtos.ReplayRequest(
                sessionId, null, null, null, placeholder, null, 4));
        mockMvc.perform(post("/api/replay")
                        .contentType(MediaType.APPLICATION_JSON).content(replay))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeholderParents[0]").value("ghost"));
    }

    @Test
    void sampleIncidentLoadsAndShowsFailure() throws Exception {
        String response = mockMvc.perform(post("/api/logs/sample"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long logId = mapper.readTree(response).get("id").asLong();
        mockMvc.perform(post("/api/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("logId", logId, "bound", 16))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay.earliestFailure").exists())
                .andExpect(jsonPath("$.replay.minimalSlice.steps").isArray())
                .andExpect(jsonPath("$.replay.interleavings.truncatedUnknown").isBoolean());
    }
}
