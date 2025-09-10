-- Use the table name from com.lemline.runner.models.DefinitionModel
CREATE TABLE IF NOT EXISTS lemline_definitions
(
    name       VARCHAR(255) COLLATE "C" NOT NULL,
    version    VARCHAR(255) COLLATE "C" NOT NULL,
    definition TEXT                     NOT NULL,
    created_at TIMESTAMPTZ(6)           NOT NULL,
    updated_at TIMESTAMPTZ(6),
    PRIMARY KEY (name, version)
);

-- Create an index for efficient querying on name
CREATE INDEX IF NOT EXISTS idx_lemline_definitions_name ON lemline_definitions (name);
