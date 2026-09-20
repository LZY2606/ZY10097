package com.example.causalworkbench.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ingestion_batch")
public class IngestionBatch {
    @Id
    private String batchId;

    @Column(nullable = false)
    private String receivedAt;

    @Column(nullable = false, length = 32000)
    private String requestFingerprint;

    @Column(nullable = false, length = 4000)
    private String summaryJson;

    protected IngestionBatch() {
    }

    public IngestionBatch(String batchId, String receivedAt, String requestFingerprint, String summaryJson) {
        this.batchId = batchId;
        this.receivedAt = receivedAt;
        this.requestFingerprint = requestFingerprint;
        this.summaryJson = summaryJson;
    }

    public String getBatchId() { return batchId; }
    public String getReceivedAt() { return receivedAt; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getSummaryJson() { return summaryJson; }
}
