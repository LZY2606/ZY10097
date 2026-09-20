package com.example.causalworkbench.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "raw_event", indexes = {
        @Index(name = "idx_raw_receipt", columnList = "receiptId"),
        @Index(name = "idx_raw_identity", columnList = "eventId"),
        @Index(name = "idx_raw_key_seq", columnList = "serviceName,seqNo")
})
public class RawEvent {
    @Id
    private String receiptId;

    @Column(nullable = false)
    private String batchId;

    @Column(nullable = false)
    private long receivedOrder;

    @Column(nullable = false)
    private String receivedAt;

    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private String serviceName;

    @Column(nullable = false)
    private long seqNo;

    @Column(nullable = false)
    private String aggregateKey;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, length = 16000)
    private String payloadJson;

    private String parentEventId;

    private Long wallClockMillis;

    @Column(nullable = false, length = 8000)
    private String rawJson;

    @Column(nullable = false)
    private String contentHash;

    @Column(nullable = false)
    private String classification;

    @Column(length = 4000)
    private String classificationReason;

    @Column(nullable = false)
    private boolean materialized;

    protected RawEvent() {
    }

    public RawEvent(String receiptId, String batchId, long receivedOrder, String receivedAt, String eventId,
                    String serviceName, long seqNo, String aggregateKey, String eventType, String payloadJson,
                    String parentEventId, Long wallClockMillis, String rawJson, String contentHash,
                    String classification, String classificationReason, boolean materialized) {
        this.receiptId = receiptId;
        this.batchId = batchId;
        this.receivedOrder = receivedOrder;
        this.receivedAt = receivedAt;
        this.eventId = eventId;
        this.serviceName = serviceName;
        this.seqNo = seqNo;
        this.aggregateKey = aggregateKey;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.parentEventId = parentEventId;
        this.wallClockMillis = wallClockMillis;
        this.rawJson = rawJson;
        this.contentHash = contentHash;
        this.classification = classification;
        this.classificationReason = classificationReason;
        this.materialized = materialized;
    }

    public String getReceiptId() { return receiptId; }
    public String getBatchId() { return batchId; }
    public long getReceivedOrder() { return receivedOrder; }
    public String getReceivedAt() { return receivedAt; }
    public String getEventId() { return eventId; }
    public String getServiceName() { return serviceName; }
    public long getSeqNo() { return seqNo; }
    public String getAggregateKey() { return aggregateKey; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public String getParentEventId() { return parentEventId; }
    public Long getWallClockMillis() { return wallClockMillis; }
    public String getRawJson() { return rawJson; }
    public String getContentHash() { return contentHash; }
    public String getClassification() { return classification; }
    public String getClassificationReason() { return classificationReason; }
    public boolean isMaterialized() { return materialized; }
}
