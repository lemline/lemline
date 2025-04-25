CREATE TABLE timeouts
(
    id            VARCHAR(36)  NOT NULL,
    instanceId    VARCHAR(36)  NOT NULL,
    position      VARCHAR(255) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    delayed_until TIMESTAMP    NOT NULL,
    attempt_count INTEGER      NOT NULL,
    last_error    TEXT,
    version       BIGINT,
    CONSTRAINT pk_timeouts PRIMARY KEY (id)
);

CREATE INDEX idx_timeouts_id_position ON timeouts (instanceId, position);

CREATE INDEX idx_timeouts_ready ON timeouts (status, delayed_until, attempt_count); 