-- Use the table name from com.lemline.runner.models.DefinitionModel
CREATE TABLE IF NOT EXISTS lemline_definitions
(
    id         VARCHAR(36),
    name       VARCHAR(255) NOT NULL,
    version    VARCHAR(255) NOT NULL,
    definition CLOB         NOT NULL,
    CONSTRAINT pk_lemline_definitions_name_version PRIMARY KEY (name, version),
    CONSTRAINT uk_lemline_definitions_id UNIQUE (id)
);

-- Create an index for efficient querying on name
CREATE INDEX IF NOT EXISTS idx_lemline_definitions_name ON lemline_definitions (name);
