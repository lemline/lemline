-- Use the table name from com.lemline.runner.models.DefinitionModel
CREATE TABLE IF NOT EXISTS lemline_definitions
(
    id         VARCHAR(36),
    name       VARCHAR(255) NOT NULL,
    version    VARCHAR(255) NOT NULL,
    definition MEDIUMTEXT   NOT NULL,
    PRIMARY KEY (name, version),
    UNIQUE KEY uk_lemline_definitions_id (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Create an index for efficient querying on name
CREATE INDEX idx_lemline_definitions_name ON lemline_definitions (name);
