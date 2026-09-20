package com.example.causalworkbench.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "candidate", indexes = {
        @Index(name = "idx_candidate_session", columnList = "sessionId"),
        @Index(name = "idx_candidate_parent", columnList = "parentCandidateId")
})
public class Candidate {
    @Id
    private String id;

    @Column(nullable = false)
    private String sessionId;

    private String parentCandidateId;

    @Column(nullable = false)
    private String createdAt;

    @Column(nullable = false, length = 16000)
    private String hypothesisJson;

    @Column(nullable = false)
    private String ruleFingerprint;

    @Column(nullable = false)
    private String sourceLogFingerprint;

    @Column(nullable = false, length = 64000)
    private String resultJson;

    @Column(nullable = false)
    private String status;

    @Column(length = 4000)
    private String invalidationReason;

    @Version
    private long revision;

    protected Candidate() {
    }

    public Candidate(String id, String sessionId, String parentCandidateId, String createdAt, String hypothesisJson,
                     String ruleFingerprint, String sourceLogFingerprint, String resultJson, String status,
                     String invalidationReason) {
        this.id = id;
        this.sessionId = sessionId;
        this.parentCandidateId = parentCandidateId;
        this.createdAt = createdAt;
        this.hypothesisJson = hypothesisJson;
        this.ruleFingerprint = ruleFingerprint;
        this.sourceLogFingerprint = sourceLogFingerprint;
        this.resultJson = resultJson;
        this.status = status;
        this.invalidationReason = invalidationReason;
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getParentCandidateId() { return parentCandidateId; }
    public String getCreatedAt() { return createdAt; }
    public String getHypothesisJson() { return hypothesisJson; }
    public String getRuleFingerprint() { return ruleFingerprint; }
    public String getSourceLogFingerprint() { return sourceLogFingerprint; }
    public String getResultJson() { return resultJson; }
    public String getStatus() { return status; }
    public String getInvalidationReason() { return invalidationReason; }
    public long getRevision() { return revision; }

    public void mark(String status, String invalidationReason, String resultJson) {
        this.status = status;
        this.invalidationReason = invalidationReason;
        if (resultJson != null) {
            this.resultJson = resultJson;
        }
    }
}
