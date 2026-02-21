CREATE TABLE IF NOT EXISTS lemline_gateway_workflows
(
    workflow_id         BINARY(16) PRIMARY KEY,
    workflow_namespace  VARCHAR(255)   NOT NULL,
    workflow_name       VARCHAR(255)   NOT NULL,
    workflow_version    VARCHAR(255)   NOT NULL,
    input_json          MEDIUMTEXT     NOT NULL,
    zone_id             VARCHAR(64)    NOT NULL,
    request_fingerprint VARCHAR(128)   NOT NULL,
    started_at          TIMESTAMP(6)   NOT NULL,
    updated_at          TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_lemline_gateway_workflows_lookup
    ON lemline_gateway_workflows (workflow_namespace, workflow_name, workflow_version);
