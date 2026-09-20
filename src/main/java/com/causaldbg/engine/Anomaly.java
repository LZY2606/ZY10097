package com.causaldbg.engine;

/**
 * Ingestion-time anomalies detected from raw evidence alone.
 */
public record Anomaly(
        Kind kind,
        String eventUid,
        String serviceSeq,
        String detail
) {
    public enum Kind {
        DUPLICATE_DELIVERY,
        SEQ_CONTENT_CONFLICT,
        MISSING_PARENT,
        LATE_DELIVERY
    }
}
