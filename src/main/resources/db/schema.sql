CREATE TABLE IF NOT EXISTS event_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(512) NOT NULL,
    fingerprint  CHAR(64) NOT NULL,
    event_count  INT NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS raw_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    log_id         BIGINT NOT NULL,
    event_uid      VARCHAR(128) NOT NULL,
    service        VARCHAR(128) NOT NULL,
    seq            BIGINT NOT NULL,
    corr_key       VARCHAR(512) NOT NULL,
    parent_uid     VARCHAR(128),
    event_type     VARCHAR(128) NOT NULL,
    payload_hash   VARCHAR(128),
    wall_clock     TIMESTAMP WITH TIME ZONE,
    received_index BIGINT NOT NULL,
    CONSTRAINT fk_raw_event_log FOREIGN KEY (log_id) REFERENCES event_log(id)
);
CREATE INDEX IF NOT EXISTS idx_raw_event_log ON raw_event(log_id);

CREATE TABLE IF NOT EXISTS debug_session (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                 VARCHAR(512) NOT NULL,
    log_id               BIGINT NOT NULL,
    log_fingerprint      CHAR(64) NOT NULL,
    rule_version         VARCHAR(64) NOT NULL,
    rule_json            CLOB NOT NULL,
    hypothesis_json      CLOB NOT NULL,
    revision             BIGINT NOT NULL DEFAULT 0,
    invalidated          BOOLEAN NOT NULL DEFAULT FALSE,
    invalidation_reason  VARCHAR(1024),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_session_log FOREIGN KEY (log_id) REFERENCES event_log(id)
);
