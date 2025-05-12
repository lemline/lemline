-- Use the table name from DefinitionRepository (tableName = "definitions")
CREATE TABLE IF NOT EXISTS definitions
(
    id         VARCHAR(36),
    name       VARCHAR(255) NOT NULL,
    version    VARCHAR(255) NOT NULL,
    definition CLOB         NOT NULL,
    CONSTRAINT pk_definitions_name_version PRIMARY KEY (name, version),
    CONSTRAINT uk_definitions_id UNIQUE (id)
);

-- Create an index for efficient querying on name
CREATE INDEX IF NOT EXISTS idx_definitions_name ON definitions (name);
