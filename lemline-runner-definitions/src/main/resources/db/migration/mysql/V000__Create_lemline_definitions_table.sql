-- Use the table name from com.lemline.runner.models.DefinitionModel
CREATE TABLE IF NOT EXISTS lemline_definitions
(
    namespace  VARCHAR(255) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    version    VARCHAR(255) NOT NULL,
    definition MEDIUMTEXT   NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6),
    PRIMARY KEY (namespace, name, version)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Create an index for efficient querying on namespace + name
CREATE INDEX idx_lemline_definitions_namespace_name
    ON lemline_definitions (namespace, name);

-- Create composite index for efficient version ordering (used by listByName with ORDER BY version)
CREATE INDEX idx_lemline_definitions_name_version
    ON lemline_definitions (namespace, name, version);

-- Index optimized for fetching latest version (ORDER BY version DESC LIMIT 1)
-- The DESC ordering helps MySQL optimize descending scans
CREATE INDEX idx_lemline_definitions_latest
    ON lemline_definitions (namespace, name, version DESC);
