-- Use the table name from com.lemline.runner.models.DefinitionModel
CREATE TABLE IF NOT EXISTS lemline_definitions
(
    namespace  VARCHAR(255)             NOT NULL,
    name       VARCHAR(255)             NOT NULL,
    version    VARCHAR(255)             NOT NULL,
    definition CLOB                     NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (namespace, name, version)
);

-- Create an index for efficient querying on name
CREATE INDEX IF NOT EXISTS idx_lemline_definitions_namespace_name
    ON lemline_definitions (namespace, name);
