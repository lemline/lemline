CREATE TABLE IF NOT EXISTS lemline_gateway_workflows
(
    workflow_id         UUID PRIMARY KEY,
    workflow_namespace  VARCHAR(255)             NOT NULL,
    workflow_name       VARCHAR(255)             NOT NULL,
    workflow_version    VARCHAR(255)             NOT NULL,
    input_json          CLOB                     NOT NULL,
    zone_id             VARCHAR(64)              NOT NULL,
    request_fingerprint VARCHAR(128)             NOT NULL,
    started_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_lemline_gateway_workflows_lookup
    ON lemline_gateway_workflows (workflow_namespace, workflow_name, workflow_version);

