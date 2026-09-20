package com.example.causalworkbench.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "debug_session")
public class DebugSession {
    @Id
    private String id;

    private String parentSessionId;

    @Column(nullable = false)
    private String createdAt;

    @Column(nullable = false)
    private String logFingerprint;

    @Column(nullable = false)
    private String ruleCode;

    @Column(nullable = false)
    private int ruleVersion;

    @Column(nullable = false)
    private String ruleFingerprint;

    @Column(nullable = false, length = 16000)
    private String baselineGraphJson;

    @Column(nullable = false)
    private long eventReceiptCount;

    protected DebugSession() {
    }

    public DebugSession(String id, String parentSessionId, String createdAt, String logFingerprint, String ruleCode,
                        int ruleVersion, String ruleFingerprint, String baselineGraphJson, long eventReceiptCount) {
        this.id = id;
        this.parentSessionId = parentSessionId;
        this.createdAt = createdAt;
        this.logFingerprint = logFingerprint;
        this.ruleCode = ruleCode;
        this.ruleVersion = ruleVersion;
        this.ruleFingerprint = ruleFingerprint;
        this.baselineGraphJson = baselineGraphJson;
        this.eventReceiptCount = eventReceiptCount;
    }

    public String getId() { return id; }
    public String getParentSessionId() { return parentSessionId; }
    public String getCreatedAt() { return createdAt; }
    public String getLogFingerprint() { return logFingerprint; }
    public String getRuleCode() { return ruleCode; }
    public int getRuleVersion() { return ruleVersion; }
    public String getRuleFingerprint() { return ruleFingerprint; }
    public String getBaselineGraphJson() { return baselineGraphJson; }
    public long getEventReceiptCount() { return eventReceiptCount; }
}
